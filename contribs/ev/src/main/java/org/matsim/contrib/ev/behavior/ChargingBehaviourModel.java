package org.matsim.contrib.ev.behavior;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Charging behaviour model producing heterogeneous per-activity charging probabilities.
 * <p>
 * Implements the specification from the user request, including SOC-based sensitivity,
 * time-of-day preferences, home-charging preferences and iterative calibration of the
 * baseline willingness parameter {@code eta_g}.
 */
public final class ChargingBehaviourModel {

	private static final double SOC_THRESHOLD = 0.20;
	private static final double PROB_MIN = 1e-6;
	private static final double PROB_MAX = 1.0 - 1e-6;

	private final EnumMap<GroupType, GroupConfig> configs = new EnumMap<>(GroupType.class);
	private final EnumMap<GroupType, SimulationStats> simulationStats = new EnumMap<>(GroupType.class);
	private final EnumMap<GroupType, DestinationPreference> destinationPreferences = new EnumMap<>(GroupType.class);
	private final EnumMap<GroupType, CalibrationEntry> lastCalibrationEntries = new EnumMap<>(GroupType.class);
	private final EnumMap<GroupType, Double> targetRealFrequencies = new EnumMap<>(GroupType.class);
	private final double baseLearningRate;
	private final double learningRateDecay;
	private final double acReferencePower;
	private final double dcfcReferencePower;
	private int learningRateIterationOffset;

	/**
	 * Constructs the model with default configuration.
	 */
	public ChargingBehaviourModel() {
		this(ChargingBehaviourParameters.createDefault());
	}

	public ChargingBehaviourModel(ChargingBehaviourParameters parameters) {
		Objects.requireNonNull(parameters, "parameters");

		this.baseLearningRate = Math.max(0.0, parameters.getLearningRate());
		this.learningRateDecay = Math.max(0.0, parameters.getLearningRateDecay());
		this.acReferencePower = parameters.getAcReferencePower();
		this.dcfcReferencePower = parameters.getDcfcReferencePower();

		for (GroupType group : GroupType.values()) {
			ChargingBehaviourParameters.GroupParameters groupParameters = parameters.getGroupParameters(group);

			double realFrequency = groupParameters.getRealFrequency();
			GroupConfig config = new GroupConfig(logit(realFrequency), groupParameters.getLambda());
			config.setRhoHome(groupParameters.getRhoHome());
			config.setRhoAway(groupParameters.getRhoAway());

			for (TimeBand band : TimeBand.values()) {
				config.setTimePreference(band, groupParameters.getTimePreference(band));
			}

			configs.put(group, config);
			simulationStats.put(group, new SimulationStats());
			destinationPreferences.put(group, new DestinationPreference());
			targetRealFrequencies.put(group, realFrequency);
			lastCalibrationEntries.put(group, null);
		}
	}

	/**
	 * Computes charging probability for the given agent and activity without applying stochastic choice.
	 * If the SOC falls below the hard threshold or the next trip is infeasible, probability is 1.
	 *
	 * @param agent agent descriptor
	 * @param act   activity descriptor
	 * @return probability in [0,1]
	 */
	public boolean isMandatoryCharging(Activity act) {
		Objects.requireNonNull(act, "act");
		double soc = clip01(act.getSocOnArrival());
		return soc < SOC_THRESHOLD
				|| (act.getCanFinishNextTrip() != null && !act.getCanFinishNextTrip());
	}

	public double computeChargingProbability(Agent agent, Activity act) {
		Objects.requireNonNull(agent, "agent");
		Objects.requireNonNull(act, "act");

		double soc = clip01(act.getSocOnArrival());
		boolean mustCharge = soc < SOC_THRESHOLD
				|| (act.getCanFinishNextTrip() != null && !act.getCanFinishNextTrip());
		if (mustCharge) {
			return 1.0;
		}

		GroupConfig config = configs.get(agent.getGroup());
		double utility = config.getEta() + config.getLambda() * (1.0 - soc);

		if (agent.hasHomeCharger()) {
			if (act.getLocationType() == LocationType.HOME) {
				utility += config.getRhoHome();
			} else {
				utility += config.getRhoAway();
			}
		}

		utility += config.getTimePreference(act.getTimeBand());

		double probability = invLogit(utility);
		return clipProbability(probability);
	}

	/**
	 * Performs a charging decision, records statistics, and returns the outcome including chosen charger type.
	 *
	 * @param agent agent descriptor
	 * @param act   activity descriptor
	 * @param rng   random generator
	 * @return charging decision
	 */
	public ChargingDecision makeChargingDecision(Agent agent, Activity act, Random rng) {
		Objects.requireNonNull(rng, "rng");

		double soc = clip01(act.getSocOnArrival());
		boolean mustCharge = soc < SOC_THRESHOLD
				|| (act.getCanFinishNextTrip() != null && !act.getCanFinishNextTrip());

		double probability;
		boolean willCharge;
		if (mustCharge) {
			probability = 1.0;
			willCharge = true;
		} else {
			probability = computeChargingProbability(agent, act);
			willCharge = rng.nextDouble() < probability;
		}

		String chargerType = determineChargerType(agent, act, rng, willCharge);
		simulationStats.get(agent.getGroup()).recordAttempt(agent, mustCharge);
		return new ChargingDecision(probability, willCharge, chargerType, mustCharge);
	}

	/**
	 * Iteratively updates {@code eta_g} following {@code eta_g <- eta_g + kappa * (logit(p_real_g) - logit(p_sim_g))}.
	 *
	 * @param group group to update
	 * @param pReal observed target probability
	 * @param pSim  simulated probability estimate
	 */
	public CalibrationEntry updateEta(GroupType group, double pRealTotal) {
		return updateEta(group, pRealTotal, 0);
	}

	public void setLearningRateIterationOffset(int offset) {
		this.learningRateIterationOffset = Math.max(0, offset);
	}

	/**
	 * Calibrates {@code eta_g} using the decomposed mandatory/optional charging shares.
	 */
	public CalibrationEntry updateEta(GroupType group, double pRealTotal, int iteration) {
		Objects.requireNonNull(group, "group");
		if (Double.isNaN(pRealTotal)) {
			return null;
		}

		SimulationStats stats = simulationStats.get(group);
		SimulationStats.Summary summary = stats.summary();
		long population = summary.population();
		if (population == 0) {
			return null;
		}

		double pRealClipped = clip01(pRealTotal);
		double alpha = clip01((double) summary.mandatoryVehicles() / population);
		double pSimTotal = clip01((double) summary.chargedVehicles() / population);

		long optionalEligible = population - summary.mandatoryVehicles();
		double qSim = 0.0;
		double qTarget = 0.0;
		double delta = 0.0;

		double learningRate = computeLearningRate(iteration);
		GroupConfig config = configs.get(group);
		double etaBefore = config.getEta();
		double etaAfter = etaBefore;
		boolean updated = false;

		if (optionalEligible > 0) {
			qSim = clip01((double) summary.optionalChargedVehicles() / optionalEligible);

			double denom = 1.0 - alpha;
			if (denom < 1e-9) {
				denom = 1e-9;
			}
			qTarget = (pRealClipped - alpha) / denom;
			qTarget = clip01(qTarget);

			double clippedTarget = clipProbability(qTarget);
			double clippedSim = clipProbability(qSim);
			delta = logit(clippedTarget) - logit(clippedSim);

			if (learningRate > 0.0 && Double.isFinite(delta)) {
				etaAfter = etaBefore + learningRate * delta;
				config.setEta(etaAfter);
				updated = true;
			}
		}

		CalibrationEntry entry = new CalibrationEntry(iteration, group, pRealClipped, pSimTotal, alpha, qTarget,
				qSim, learningRate, delta, etaBefore, etaAfter, updated);
		lastCalibrationEntries.put(group, entry);
		return entry;
	}

	private double computeLearningRate(int iteration) {
		if (baseLearningRate <= 0.0) {
			return 0.0;
		}
		if (learningRateDecay <= 0.0) {
			return baseLearningRate;
		}
		int effectiveIteration = Math.max(0, iteration - learningRateIterationOffset);
		return baseLearningRate / (1.0 + learningRateDecay * effectiveIteration);
	}

	/**
	 * Sets SOC sensitivity {@code lambda_g}. Must be non-negative.
	 */
	public void setSocSensitivity(GroupType group, double lambda) {
		if (lambda < 0) {
			throw new IllegalArgumentException("lambda must be >= 0");
		}
		configs.get(group).setLambda(lambda);
	}

	/**
	 * Sets time preference {@code tau_{g,h}} for the given group and time band.
	 */
	public void setTimePreference(GroupType group, TimeBand band, double tau) {
		configs.get(group).setTimePreference(band, tau);
	}

	/**
	 * Computes {@code rho_home_g} and {@code rho_away_g} from survey counts and plan arrivals.
	 *
	 * @param group group identifier
	 * @param nHome survey count home
	 * @param nDest survey count destination
	 * @param nFast survey count fast chargers
	 * @param eHome arrivals with home charger at home
	 * @param eAway arrivals with home charger away from home
	 */
	public void setHomeChargingPreferences(GroupType group, double nHome, double nDest, double nFast, double eHome,
			double eAway) {
		if (nHome < 0 || nDest < 0 || nFast < 0) {
			throw new IllegalArgumentException("Survey counts must be non-negative.");
		}
		if (eHome <= 0 || eAway <= 0) {
			throw new IllegalArgumentException("Arrival counts must be positive.");
		}

		double countsTotal = nHome + nDest + nFast;
		if (countsTotal <= 0) {
			throw new IllegalArgumentException("Total survey counts must be positive.");
		}

		double eventsTotal = eHome + eAway;
		if (eventsTotal <= 0) {
			throw new IllegalArgumentException("Total arrival events must be positive.");
		}

		double sStar = nHome / countsTotal;
		double deltaRho = Math.log((sStar / (1.0 - sStar)) * (eAway / eHome));
		double wHome = eHome / eventsTotal;
		double wAway = 1.0 - wHome;

		GroupConfig config = configs.get(group);
		config.setRhoHome(wAway * deltaRho);
		config.setRhoAway(-wHome * deltaRho);
	}

	/**
	 * Configures destination charger selection odds (AC vs DCFC) for the given group.
	 *
	 * @param group       group identifier
	 * @param alphaDestAc log-odds weight for destination AC relative to DCFC
	 * @param alphaDcfc   log-odds weight for DCFC (baseline usually zero)
	 */
	public void setDestinationLogOdds(GroupType group, double alphaDestAc, double alphaDcfc) {
		destinationPreferences.get(group).setLogOdds(alphaDestAc, alphaDcfc);
	}

	/**
	 * Returns simulated charging frequency for the given group, based on the decisions made so far.
	 *
	 * @param group group identifier
	 * @return charges / attempts, or {@code Double.NaN} if no attempts observed
	 */
	public double getSimulatedChargingRate(GroupType group) {
		return simulationStats.get(group).totalChargeRate();
	}

	/**
	 * Returns {@code alpha}: share of vehicles that experienced at least one mandatory charge.
	 */
	public double getMandatoryShare(GroupType group) {
		return simulationStats.get(group).mandatoryShare();
	}

	/**
	 * Returns {@code qSim}: share of optional charges among vehicles without mandatory charges.
	 */
	public double getOptionalChargeShare(GroupType group) {
		return simulationStats.get(group).optionalChargeShare();
	}

	/**
	 * Resets per-group simulation statistics (e.g. when starting a new iteration).
	 */
	public void resetSimulationStats() {
		simulationStats.values().forEach(SimulationStats::reset);
	}

	/**
	 * Exposes current {@code eta_g} for diagnostics.
	 */
	public double getEta(GroupType group) {
		return configs.get(group).getEta();
	}

	public void registerGroupMember(GroupType group, Object key) {
		simulationStats.get(group).registerPopulationMember(key);
	}

	public void recordChargingOutcome(GroupType group, Object key, boolean success, boolean mandatory) {
		if (key == null) {
			return;
		}
		simulationStats.get(group).recordOutcome(key, success, mandatory);
	}

	public double getTargetRealFrequency(GroupType group) {
		return targetRealFrequencies.get(group);
	}

	public double getAcReferencePower() {
		return acReferencePower;
	}

	public double getDcfcReferencePower() {
		return dcfcReferencePower;
	}

	public CalibrationEntry getLastCalibrationEntry(GroupType group) {
		return lastCalibrationEntries.get(group);
	}

	private String determineChargerType(Agent agent, Activity act, Random rng, boolean willCharge) {
		if (!willCharge) {
			return "none";
		}

		if (act.getLocationType() == LocationType.HOME) {
			return agent.hasHomeCharger() ? "home" : "DCFC";
		}

		if (act.getLocationType() == LocationType.DESTINATION) {
			return destinationPreferences.get(agent.getGroup()).choose(rng);
		}

		return "DCFC";
	}

	private static double clip01(double value) {
		if (value < 0.0) {
			return 0.0;
		}
		if (value > 1.0) {
			return 1.0;
		}
		return value;
	}

	private static double clipProbability(double p) {
		if (p < PROB_MIN) {
			return PROB_MIN;
		}
		if (p > PROB_MAX) {
			return PROB_MAX;
		}
		return p;
	}

	private static double logit(double p) {
		double clipped = clipProbability(p);
		return Math.log(clipped / (1.0 - clipped));
	}

	private static double invLogit(double z) {
		if (z >= 0) {
			double expNeg = Math.exp(-z);
			return clipProbability(1.0 / (1.0 + expNeg));
		} else {
			double expPos = Math.exp(z);
			return clipProbability(expPos / (1.0 + expPos));
		}
	}

	private static final class GroupConfig {
		private double eta;
		private double lambda;
		private double rhoHome;
		private double rhoAway;
		private final EnumMap<TimeBand, Double> timePreferences = new EnumMap<>(TimeBand.class);

		GroupConfig(double eta, double lambda) {
			this.eta = eta;
			this.lambda = lambda;
			for (TimeBand band : TimeBand.values()) {
				timePreferences.put(band, 0.0);
			}
		}

		double getEta() {
			return eta;
		}

		void setEta(double eta) {
			this.eta = eta;
		}

		double getLambda() {
			return lambda;
		}

		void setLambda(double lambda) {
			this.lambda = lambda;
		}

		double getRhoHome() {
			return rhoHome;
		}

		void setRhoHome(double rhoHome) {
			this.rhoHome = rhoHome;
		}

		double getRhoAway() {
			return rhoAway;
		}

		void setRhoAway(double rhoAway) {
			this.rhoAway = rhoAway;
		}

		double getTimePreference(TimeBand band) {
			return timePreferences.getOrDefault(band, 0.0);
		}

		void setTimePreference(TimeBand band, double value) {
			timePreferences.put(band, value);
		}
	}

	private static final class SimulationStats {
		private final Map<Object, AgentDayStats> agentStats = new HashMap<>();

		void recordAttempt(Agent agent, boolean mandatory) {
			Object key = agent.getPersonId() != null ? agent.getPersonId() : agent;
			AgentDayStats stats = agentStats.computeIfAbsent(key, k -> new AgentDayStats());
			if (mandatory) {
				stats.hasMandatory = true;
			}
		}

		void recordOutcome(Object key, boolean success, boolean mandatory) {
			AgentDayStats stats = agentStats.computeIfAbsent(key, k -> new AgentDayStats());
			if (mandatory) {
				stats.hasMandatory = true;
			}
			if (success) {
				stats.hasCharged = true;
				if (!mandatory) {
					stats.hasOptionalCharge = true;
				}
			}
		}

		void registerPopulationMember(Object key) {
			agentStats.computeIfAbsent(key, k -> new AgentDayStats());
		}

		double totalChargeRate() {
			Summary summary = summary();
			if (summary.population() == 0) {
				return Double.NaN;
			}
			return clip01((double) summary.chargedVehicles() / summary.population());
		}

		double mandatoryShare() {
			Summary summary = summary();
			if (summary.population() == 0) {
				return Double.NaN;
			}
			return clip01((double) summary.mandatoryVehicles() / summary.population());
		}

		double optionalChargeShare() {
			Summary summary = summary();
			long eligible = summary.population() - summary.mandatoryVehicles();
			if (eligible <= 0) {
				return 0.0;
			}
			return clip01((double) summary.optionalChargedVehicles() / eligible);
		}

		Summary summary() {
			long population = agentStats.size();
			long mandatory = 0;
			long optionalCharged = 0;
			long charged = 0;
			for (AgentDayStats stats : agentStats.values()) {
				if (stats.hasMandatory) {
					mandatory++;
				}
				if (!stats.hasMandatory && stats.hasOptionalCharge) {
					optionalCharged++;
				}
				if (stats.hasCharged) {
					charged++;
				}
			}
			return new Summary(population, mandatory, optionalCharged, charged);
		}

		void reset() {
			agentStats.clear();
		}

		private static final class AgentDayStats {
			boolean hasMandatory;
			boolean hasOptionalCharge;
			boolean hasCharged;
		}

		record Summary(long population, long mandatoryVehicles, long optionalChargedVehicles, long chargedVehicles) {
		}
	}

	public record CalibrationEntry(int iteration, GroupType group, double pRealTotal, double pSimTotal, double alpha,
			double qTarget, double qSim, double learningRate, double delta, double etaBefore, double etaAfter,
			boolean updated) {
	}

	private static final class DestinationPreference {
		private double alphaDestAc;
		private double alphaDcfc;

		void setLogOdds(double alphaDestAc, double alphaDcfc) {
			this.alphaDestAc = alphaDestAc;
			this.alphaDcfc = alphaDcfc;
		}

		String choose(Random rng) {
			// Max-shift softmax: identical to exp(α)/Σ exp(α) but stable for large |α|.
			double maxAlpha = Math.max(alphaDestAc, alphaDcfc);
			double weightAc = Math.exp(alphaDestAc - maxAlpha);
			double weightDc = Math.exp(alphaDcfc - maxAlpha);
			double sum = weightAc + weightDc;
			if (!Double.isFinite(sum) || sum <= 0.0) {
				return "destAC";
			}
			double draw = rng.nextDouble();
			return draw < weightAc / sum ? "destAC" : "DCFC";
		}
	}

	/**
	 * Simple demonstration of the model with sample agents and activities.
	 */
	public static void main(String[] args) {
		ChargingBehaviourModel model = new ChargingBehaviourModel();
		Random rng = new Random(42L);

		Agent apartmentResident = new Agent(GroupType.APARTMENT, false);
		Agent pvHousehold = new Agent(GroupType.HOUSE_WITH_PV, true);

		Activity destEvening = new Activity(LocationType.DESTINATION, TimeBand.EVENING_16_22, 0.35, true);
		Activity homeMorningLowSoc = new Activity(LocationType.HOME, TimeBand.MORNING_6_10, 0.18);

		double prob1 = model.computeChargingProbability(apartmentResident, destEvening);
		ChargingDecision decision1 = model.makeChargingDecision(apartmentResident, destEvening, rng);

		double prob2 = model.computeChargingProbability(pvHousehold, homeMorningLowSoc);
		ChargingDecision decision2 = model.makeChargingDecision(pvHousehold, homeMorningLowSoc, rng);

		System.out.println("Apartment destination probability: " + prob1 + " decision: " + decision1);
		System.out.println("PV household home probability: " + prob2 + " decision: " + decision2);

		double simulatedRate = model.getSimulatedChargingRate(GroupType.APARTMENT);
		System.out.println("Simulated rate (APARTMENT): " + simulatedRate);

		CalibrationEntry entry = model.updateEta(GroupType.APARTMENT,
				model.targetRealFrequencies.get(GroupType.APARTMENT));
		double adjustedProb = model.computeChargingProbability(apartmentResident, destEvening);
		System.out.println("Adjusted probability after calibration: " + adjustedProb);
		if (entry != null) {
			System.out.println("Calibration entry: " + entry);
		}
	}
}

