package org.matsim.contrib.ev.behavior;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.geometry.CoordUtils;

/**
 * Sequential H/W/D/F charging-start and supply-location model from the future access paper.
 */
public final class FutureChargingBehaviourModel {
	private static final double PROB_MIN = 1e-6;
	private static final double PROB_MAX = 1.0 - 1e-6;
	private static final int PUBLIC_START_SOC_BINS = 10;

	private final EnumMap<GroupType, GroupConfig> configs = new EnumMap<>(GroupType.class);
	private final EnumMap<GroupType, SimulationStats> simulationStats = new EnumMap<>(GroupType.class);
	private final EnumMap<GroupType, LatentDemandDiagnostics> latentDemandDiagnostics = new EnumMap<>(GroupType.class);
	private final EnumMap<GroupType, CalibrationEntry> lastCalibrationEntries = new EnumMap<>(GroupType.class);
	private final EnumMap<GroupType, Double> targetRealFrequencies = new EnumMap<>(GroupType.class);
	private final double baseLearningRate;
	private final double learningRateDecay;
	private final double minimumSoc;
	private final double activitySelectionTemperature;
	private final double acReferencePower;
	private final double dcfcReferencePower;
	private final boolean workplaceChargingAvailable;
	private final boolean destinationChargingAvailable;
	private final EnumSet<FutureChargingActivityLabel> destinationChargingActivityLabels;
	private final boolean publicFastFallback;
	private final boolean latentPublicDemand;
	private final boolean unrestrictedLatentPublicDemand;
	private final double latentWorkplaceServiceRate;
	private final double latentDestinationServiceRate;
	private final double minimumLatentActivityChargingDuration;
	private final double minimumLatentFastChargingDuration;
	private final double maximumLatentPublicFastSoc;
	private final double publicStartSocWeightExponent;
	private final double latentPublicFastSearchRadius;
	private final String latentPublicDemandFile;
	private final List<LatentPublicDemandRecord> latentPublicDemandRecords = new LinkedList<>();
	private final double[] publicStartSocWeights = new double[PUBLIC_START_SOC_BINS];
	private QuadTree<Coord> latentPublicFastCandidateIndex;
	private int learningRateIterationOffset;

	public FutureChargingBehaviourModel(FutureChargingBehaviourConfigGroup cfg,
			FutureChargingBehaviourParameters parameters) {
		Objects.requireNonNull(cfg, "cfg");
		Objects.requireNonNull(parameters, "parameters");

		this.baseLearningRate = cfg.getLearningRate();
		this.learningRateDecay = cfg.getLearningRateDecay();
		this.minimumSoc = cfg.getMinimumSoc();
		this.activitySelectionTemperature = cfg.getActivitySelectionTemperature();
		this.acReferencePower = cfg.getAcReferencePower();
		this.dcfcReferencePower = cfg.getDcfcReferencePower();
		this.workplaceChargingAvailable = cfg.isWorkplaceChargingAvailable();
		this.destinationChargingAvailable = cfg.isDestinationChargingAvailable();
		this.destinationChargingActivityLabels = cfg.getDestinationChargingActivityLabels();
		this.publicFastFallback = cfg.isPublicFastFallback();
		this.latentPublicDemand = cfg.isLatentPublicDemand();
		this.unrestrictedLatentPublicDemand = cfg.isUnrestrictedLatentPublicDemand();
		this.latentWorkplaceServiceRate = cfg.getLatentWorkplaceServiceRate();
		this.latentDestinationServiceRate = cfg.getLatentDestinationServiceRate();
		this.minimumLatentActivityChargingDuration = cfg.getMinimumLatentActivityChargingDuration();
		this.minimumLatentFastChargingDuration = cfg.getMinimumLatentFastChargingDuration();
		this.maximumLatentPublicFastSoc = cfg.getMaximumLatentPublicFastSoc();
		this.publicStartSocWeightExponent = cfg.getPublicStartSocWeightExponent();
		this.latentPublicFastSearchRadius = cfg.getLatentPublicFastSearchRadius();
		this.latentPublicDemandFile = cfg.getLatentPublicDemandFile();
		for (int i = 0; i < publicStartSocWeights.length; i++) {
			publicStartSocWeights[i] = 1.0;
		}

		for (GroupType group : GroupType.values()) {
			FutureChargingBehaviourParameters.GroupParameters groupParameters = parameters.getGroupParameters(group);
			GroupConfig config = new GroupConfig(logit(groupParameters.getRealFrequency()), groupParameters.getLambda());
			config.setRhoHome(groupParameters.getRhoHome());
			config.setRhoAway(groupParameters.getRhoAway());
			for (TimeBand band : TimeBand.values()) {
				config.setTimePreference(band, groupParameters.getTimePreference(band));
			}
			configs.put(group, config);
			simulationStats.put(group, new SimulationStats());
			latentDemandDiagnostics.put(group, new LatentDemandDiagnostics());
			targetRealFrequencies.put(group, groupParameters.getRealFrequency());
			lastCalibrationEntries.put(group, null);
		}
	}

	public double computeChargingProbability(Agent agent, FutureChargingActivity act) {
		Objects.requireNonNull(agent, "agent");
		Objects.requireNonNull(act, "act");

		double soc = clip01(act.getSocOnArrival());
		if (isMandatory(act, soc)) {
			return 1.0;
		}

		return clipProbability(invLogit(computeChargingUtility(agent, act)));
	}

	public double computeChargingUtility(Agent agent, FutureChargingActivity act) {
		Objects.requireNonNull(agent, "agent");
		Objects.requireNonNull(act, "act");

		double soc = clip01(act.getSocOnArrival());
		GroupConfig config = configs.get(agent.getGroup());
		double utility = config.getEta() + config.getLambda() * (1.0 - soc);
		if (agent.hasHomeCharger()) {
			utility += act.isHome() ? config.getRhoHome() : config.getRhoAway();
		}
		utility += config.getTimePreference(act.getTimeBand());
		return utility;
	}

	public FutureChargingDecision makeChargingDecision(Agent agent, FutureChargingActivity act, Random rng) {
		Objects.requireNonNull(rng, "rng");
		double soc = clip01(act.getSocOnArrival());
		boolean mandatory = isMandatory(act, soc);
		double probability = mandatory ? 1.0 : computeChargingProbability(agent, act);
		boolean willCharge = mandatory || rng.nextDouble() < probability;
		FutureChargingSupplyType supplyType = willCharge ? chooseSupplyType(agent.getGroup(), act, rng, mandatory)
				: FutureChargingSupplyType.FAST;
		simulationStats.get(agent.getGroup()).recordAttempt(agent, mandatory);
		return new FutureChargingDecision(probability, willCharge, supplyType, mandatory,
				computeOpportunityValue(agent.getGroup(), act));
	}

	public FutureChargingDecision makeLatentPublicDemandDecision(Agent agent, FutureChargingActivity act, Random rng) {
		Objects.requireNonNull(rng, "rng");
		double soc = clip01(act.getSocOnArrival());
		boolean mandatory = isMandatory(act, soc);
		double probability = mandatory ? 1.0 : computeChargingProbability(agent, act);
		FutureChargingSupplyType supplyType = chooseSupplyType(agent.getGroup(), act, rng, mandatory);
		probability = adjustLatentStartProbability(probability, supplyType, act, mandatory);
		boolean willCharge = mandatory || rng.nextDouble() < probability;
		simulationStats.get(agent.getGroup()).recordAttempt(agent, mandatory);
		return new FutureChargingDecision(probability, willCharge, supplyType, mandatory,
				computeOpportunityValue(agent.getGroup(), act));
	}

	public boolean isMandatoryCharging(FutureChargingActivity act) {
		Objects.requireNonNull(act, "act");
		return isMandatory(act, clip01(act.getSocOnArrival()));
	}

	public boolean isLatentPublicDemandEnabled() {
		return latentPublicDemand;
	}

	public boolean isUnrestrictedLatentPublicDemand() {
		return unrestrictedLatentPublicDemand;
	}

	public String getLatentPublicDemandFile() {
		return latentPublicDemandFile;
	}

	public boolean isPublicSupplyType(FutureChargingSupplyType type) {
		return type == FutureChargingSupplyType.FAST;
	}

	public boolean isLatentDemandSupplyType(FutureChargingSupplyType type) {
		return type == FutureChargingSupplyType.WORKPLACE || type == FutureChargingSupplyType.DESTINATION
				|| type == FutureChargingSupplyType.FAST;
	}

	public double getLatentWorkplaceServiceRate() {
		return latentWorkplaceServiceRate;
	}

	public double getLatentDestinationServiceRate() {
		return latentDestinationServiceRate;
	}

	public boolean isDestinationChargingActivity(FutureChargingActivityLabel label) {
		return destinationChargingActivityLabels.contains(label);
	}

	public double getMinimumLatentActivityChargingDuration() {
		return minimumLatentActivityChargingDuration;
	}

	public double getMinimumLatentFastChargingDuration() {
		return minimumLatentFastChargingDuration;
	}

	public double getMaximumLatentPublicFastSoc() {
		return maximumLatentPublicFastSoc;
	}

	public double getPublicStartSocWeightExponent() {
		return publicStartSocWeightExponent;
	}

	public double getLatentPublicFastSearchRadius() {
		return latentPublicFastSearchRadius;
	}

	public double getActivitySelectionTemperature() {
		return activitySelectionTemperature;
	}

	public synchronized void recordLatentPublicDemand(LatentPublicDemandRecord record) {
		latentPublicDemandRecords.add(Objects.requireNonNull(record, "record"));
	}

	public synchronized List<LatentPublicDemandRecord> consumeLatentPublicDemandRecords() {
		List<LatentPublicDemandRecord> records = List.copyOf(latentPublicDemandRecords);
		latentPublicDemandRecords.clear();
		return records;
	}

	public void setPublicStartSocWeights(double[] weights) {
		if (weights == null || weights.length != PUBLIC_START_SOC_BINS) {
			throw new IllegalArgumentException("Expected " + PUBLIC_START_SOC_BINS + " public start-SOC weights");
		}
		for (int i = 0; i < weights.length; i++) {
			publicStartSocWeights[i] = Math.max(0.0, Math.min(1.0, weights[i]));
		}
	}

	public double getPublicStartSocWeight(double soc) {
		int bin = Math.min(PUBLIC_START_SOC_BINS - 1,
				Math.max(0, (int) Math.floor(clip01(soc) * PUBLIC_START_SOC_BINS)));
		return publicStartSocWeights[bin];
	}

	public double adjustLatentFastStartProbability(double probability, FutureChargingActivity act, boolean mandatory) {
		return adjustLatentStartProbability(probability, FutureChargingSupplyType.FAST, act, mandatory);
	}

	public void setLatentPublicFastCandidates(List<Coord> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			latentPublicFastCandidateIndex = null;
			return;
		}
		double minX = Double.POSITIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxY = Double.NEGATIVE_INFINITY;
		for (Coord candidate : candidates) {
			if (candidate == null || !Double.isFinite(candidate.getX()) || !Double.isFinite(candidate.getY())) {
				continue;
			}
			minX = Math.min(minX, candidate.getX());
			minY = Math.min(minY, candidate.getY());
			maxX = Math.max(maxX, candidate.getX());
			maxY = Math.max(maxY, candidate.getY());
		}
		if (!Double.isFinite(minX)) {
			latentPublicFastCandidateIndex = null;
			return;
		}
		double epsilon = 1.0;
		QuadTree<Coord> index = new QuadTree<>(minX - epsilon, minY - epsilon, maxX + epsilon, maxY + epsilon);
		for (Coord candidate : candidates) {
			if (candidate != null && Double.isFinite(candidate.getX()) && Double.isFinite(candidate.getY())) {
				index.put(candidate.getX(), candidate.getY(), candidate);
			}
		}
		latentPublicFastCandidateIndex = index;
	}

	public boolean hasLatentPublicFastCandidateNear(Coord coord) {
		if (latentPublicFastCandidateIndex == null) {
			return true;
		}
		if (coord == null || !Double.isFinite(coord.getX()) || !Double.isFinite(coord.getY())) {
			return false;
		}
		if (latentPublicFastSearchRadius == Double.POSITIVE_INFINITY) {
			return true;
		}
		Coord nearest = latentPublicFastCandidateIndex.getClosest(coord.getX(), coord.getY());
		return nearest != null && CoordUtils.calcEuclideanDistance(coord, nearest) <= latentPublicFastSearchRadius;
	}

	public EnumSet<FutureChargingSupplyType> createFeasibleSupplyTypes(FutureChargingActivityLabel label,
			boolean hasHomeCharger, boolean mandatory) {
		return createFeasibleSupplyTypes(label, hasHomeCharger, mandatory, true);
	}

	public EnumSet<FutureChargingSupplyType> createFeasiblePublicSupplyTypes(FutureChargingActivityLabel label,
			boolean hasHomeCharger, boolean mandatory) {
		EnumSet<FutureChargingSupplyType> feasible = createFeasibleSupplyTypes(label, hasHomeCharger, mandatory, false);
		feasible.removeIf(type -> !isPublicSupplyType(type));
		return feasible;
	}

	private EnumSet<FutureChargingSupplyType> createFeasibleSupplyTypes(FutureChargingActivityLabel label,
			boolean hasHomeCharger, boolean mandatory, boolean useFallback) {
		EnumSet<FutureChargingSupplyType> feasible = EnumSet.noneOf(FutureChargingSupplyType.class);
		switch (label) {
			case HOME -> {
				if (hasHomeCharger) {
					feasible.add(FutureChargingSupplyType.HOME);
				} else if (publicFastFallback) {
					feasible.add(FutureChargingSupplyType.FAST);
				}
			}
			case WORK -> {
				if (workplaceChargingAvailable) {
					feasible.add(FutureChargingSupplyType.WORKPLACE);
				}
				if (publicFastFallback) {
					feasible.add(FutureChargingSupplyType.FAST);
				}
			}
			case SHOP, STUDY, SOCIAL_RECREATIONAL, OTHER, PERSONAL, PICKUP_DROPOFF_DELIVER, WITH_SOMEONE -> {
				if (isDestinationChargingActivity(label) && destinationChargingAvailable) {
					feasible.add(FutureChargingSupplyType.DESTINATION);
				}
				if (publicFastFallback) {
					feasible.add(FutureChargingSupplyType.FAST);
				}
			}
			case MODE_CHANGE -> {
				if (mandatory && publicFastFallback) {
					feasible.add(FutureChargingSupplyType.FAST);
				}
			}
			default -> {
				if (publicFastFallback) {
					feasible.add(FutureChargingSupplyType.FAST);
				}
			}
		}
		if (useFallback && feasible.isEmpty()) {
			feasible.add(FutureChargingSupplyType.FAST);
		}
		return feasible;
	}

	public double computeOpportunityValue(GroupType group, FutureChargingActivity act) {
		if (act.isHome()) {
			return 0.0;
		}
		GroupConfig config = configs.get(group);
		Set<FutureChargingSupplyType> feasible = act.getFeasibleSupplyTypes();
		if (feasible.isEmpty()) {
			return 0.0;
		}
		// Log-sum-exp with max-shift: log Σ exp(α_i) = max + log Σ exp(α_i - max).
		// Keeps all exp arguments ≤ 0, avoiding overflow when supply preferences become large.
		double maxAlpha = Double.NEGATIVE_INFINITY;
		for (FutureChargingSupplyType type : feasible) {
			double alpha = config.getSupplyPreference(type);
			if (alpha > maxAlpha) {
				maxAlpha = alpha;
			}
		}
		double sumShifted = 0.0;
		for (FutureChargingSupplyType type : feasible) {
			sumShifted += Math.exp(config.getSupplyPreference(type) - maxAlpha);
		}
		double logSumExp = maxAlpha + Math.log(Math.max(1e-12, sumShifted));
		return logSumExp - config.getSupplyPreference(FutureChargingSupplyType.FAST);
	}

	public void setSupplyPreference(GroupType group, FutureChargingSupplyType type, double alpha) {
		configs.get(group).setSupplyPreference(type, alpha);
	}

	public void setTimePreference(GroupType group, TimeBand band, double tau) {
		configs.get(group).setTimePreference(band, tau);
	}

	public void setSocSensitivity(GroupType group, double lambda) {
		configs.get(group).setLambda(lambda);
	}

	public void setHomeChargingPreferences(GroupType group, double nHome, double nDest, double nFast, double eHome,
			double eAway) {
		if (nHome < 0 || nDest < 0 || nFast < 0 || eHome <= 0 || eAway <= 0) {
			return;
		}
		double countsTotal = nHome + nDest + nFast;
		if (countsTotal <= 0) {
			return;
		}
		double sStar = nHome / countsTotal;
		double deltaRho = Math.log((sStar / (1.0 - sStar)) * (eAway / eHome));
		double wHome = eHome / (eHome + eAway);
		GroupConfig config = configs.get(group);
		config.setRhoHome((1.0 - wHome) * deltaRho);
		config.setRhoAway(-wHome * deltaRho);
	}

	public void registerGroupMember(GroupType group, Object key) {
		simulationStats.get(group).registerPopulationMember(key);
	}

	public void recordChargingOutcome(GroupType group, Object key, boolean success, boolean mandatory) {
		if (key != null) {
			simulationStats.get(group).recordOutcome(key, success, mandatory);
		}
	}

	public void registerLatentDemandPopulationMember(GroupType group, Object key) {
		if (key != null) {
			latentDemandDiagnostics.get(group).population.add(key);
		}
	}

	public void recordLatentDemandNonHomeCandidate(GroupType group, Object key) {
		if (key != null) {
			latentDemandDiagnostics.get(group).nonHomeCandidate.add(key);
		}
	}

	public void recordLatentDemandEligibleNonHome(GroupType group, Object key) {
		if (key != null) {
			latentDemandDiagnostics.get(group).eligibleNonHome.add(key);
		}
	}

	public void recordLatentDemandPreferredSelected(GroupType group, Object key) {
		if (key != null) {
			latentDemandDiagnostics.get(group).preferredSelected.add(key);
		}
	}

	public void recordLatentDemandPreferredReached(GroupType group, Object key) {
		if (key != null) {
			latentDemandDiagnostics.get(group).preferredReached.add(key);
		}
	}

	public void recordLatentDemandDecisionEvaluated(GroupType group, Object key) {
		if (key != null) {
			latentDemandDiagnostics.get(group).decisionEvaluated.add(key);
		}
	}

	public void recordLatentDemandGenerated(GroupType group, Object key, boolean mandatory) {
		if (key != null) {
			LatentDemandDiagnostics diagnostics = latentDemandDiagnostics.get(group);
			diagnostics.demandGenerated.add(key);
			if (mandatory) {
				diagnostics.mandatoryDemand.add(key);
			} else {
				diagnostics.conditionalDemand.add(key);
			}
		}
	}

	public void recordLatentDemandSocUpdated(GroupType group, Object key) {
		if (key != null) {
			latentDemandDiagnostics.get(group).socUpdated.add(key);
		}
	}

	public double getSimulatedChargingRate(GroupType group) {
		return simulationStats.get(group).totalChargeRate();
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

	public void resetSimulationStats() {
		simulationStats.values().forEach(SimulationStats::reset);
	}

	public List<LatentDemandDiagnosticEntry> getLatentDemandDiagnosticEntries(int iteration) {
		List<LatentDemandDiagnosticEntry> entries = new LinkedList<>();
		for (GroupType group : GroupType.values()) {
			LatentDemandDiagnostics diagnostics = latentDemandDiagnostics.get(group);
			entries.add(new LatentDemandDiagnosticEntry(iteration, group, diagnostics.population.size(),
					diagnostics.nonHomeCandidate.size(), diagnostics.eligibleNonHome.size(),
					diagnostics.preferredSelected.size(), diagnostics.preferredReached.size(),
					diagnostics.decisionEvaluated.size(), diagnostics.demandGenerated.size(),
					diagnostics.mandatoryDemand.size(), diagnostics.conditionalDemand.size(),
					diagnostics.socUpdated.size()));
		}
		return entries;
	}

	public void resetLatentDemandDiagnostics() {
		latentDemandDiagnostics.values().forEach(LatentDemandDiagnostics::reset);
	}

	public void setLearningRateIterationOffset(int offset) {
		this.learningRateIterationOffset = Math.max(0, offset);
	}

	public CalibrationEntry updateEta(GroupType group, double pRealTotal, int iteration) {
		SimulationStats stats = simulationStats.get(group);
		SimulationStats.Summary summary = stats.summary();
		if (summary.population() == 0 || Double.isNaN(pRealTotal)) {
			return null;
		}
		double pRealClipped = clip01(pRealTotal);
		double alpha = clip01((double) summary.mandatoryVehicles() / summary.population());
		double pSimTotal = clip01((double) summary.chargedVehicles() / summary.population());
		long optionalEligible = summary.population() - summary.mandatoryVehicles();
		double learningRate = computeLearningRate(iteration);
		double etaBefore = configs.get(group).getEta();
		double etaAfter = etaBefore;
		double qTarget = 0.0;
		double qSim = 0.0;
		double delta = 0.0;
		boolean updated = false;
		if (optionalEligible > 0) {
			qSim = clip01((double) summary.optionalChargedVehicles() / optionalEligible);
			qTarget = clip01((pRealClipped - alpha) / Math.max(1e-9, 1.0 - alpha));
			delta = logit(clipProbability(qTarget)) - logit(clipProbability(qSim));
			if (learningRate > 0.0 && Double.isFinite(delta)) {
				etaAfter = etaBefore + learningRate * delta;
				configs.get(group).setEta(etaAfter);
				updated = true;
			}
		}
		CalibrationEntry entry = new CalibrationEntry(iteration, group, pRealClipped, pSimTotal, alpha, qTarget, qSim,
				learningRate, delta, etaBefore, etaAfter, updated);
		lastCalibrationEntries.put(group, entry);
		return entry;
	}

	private FutureChargingSupplyType chooseSupplyType(GroupType group, FutureChargingActivity act, Random rng,
			boolean mandatory) {
		Set<FutureChargingSupplyType> feasible = act.getFeasibleSupplyTypes();
		if (feasible.isEmpty()) {
			return FutureChargingSupplyType.FAST;
		}
		GroupConfig config = configs.get(group);
		// Numerically stable softmax sampling: shift all α by max so exp arguments are ≤ 0.
		// The shift cancels out in the categorical distribution, so the sampled outcome is
		// identical to the naive implementation while avoiding overflow for large preferences.
		double maxAlpha = Double.NEGATIVE_INFINITY;
		for (FutureChargingSupplyType type : feasible) {
			double alpha = adjustedSupplyPreference(config, type, act, mandatory);
			if (alpha > maxAlpha) {
				maxAlpha = alpha;
			}
		}
		double sumShifted = 0.0;
		for (FutureChargingSupplyType type : feasible) {
			sumShifted += Math.exp(adjustedSupplyPreference(config, type, act, mandatory) - maxAlpha);
		}
		if (!Double.isFinite(sumShifted) || sumShifted <= 0.0) {
			return FutureChargingSupplyType.FAST;
		}
		double draw = rng.nextDouble() * sumShifted;
		double cumulative = 0.0;
		for (FutureChargingSupplyType type : feasible) {
			cumulative += Math.exp(adjustedSupplyPreference(config, type, act, mandatory) - maxAlpha);
			if (draw <= cumulative) {
				return type;
			}
		}
		return FutureChargingSupplyType.FAST;
	}

	private double adjustedSupplyPreference(GroupConfig config, FutureChargingSupplyType type,
			FutureChargingActivity act, boolean mandatory) {
		return config.getSupplyPreference(type);
	}

	private double adjustLatentStartProbability(double probability, FutureChargingSupplyType supplyType,
			FutureChargingActivity act, boolean mandatory) {
		if (!mandatory && supplyType == FutureChargingSupplyType.FAST && publicStartSocWeightExponent > 0.0) {
			double weight = Math.pow(Math.max(PROB_MIN, getPublicStartSocWeight(act.getSocOnArrival())),
					publicStartSocWeightExponent);
			return clipProbability(probability * weight);
		}
		return probability;
	}

	private boolean isMandatory(FutureChargingActivity act, double soc) {
		return soc < minimumSoc || (act.getCanFinishNextTrip() != null && !act.getCanFinishNextTrip());
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

	private static double clip01(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	private static double clipProbability(double p) {
		return Math.max(PROB_MIN, Math.min(PROB_MAX, p));
	}

	private static double logit(double p) {
		double clipped = clipProbability(p);
		return Math.log(clipped / (1.0 - clipped));
	}

	private static double invLogit(double z) {
		if (z >= 0) {
			double expNeg = Math.exp(-z);
			return 1.0 / (1.0 + expNeg);
		}
		double expPos = Math.exp(z);
		return expPos / (1.0 + expPos);
	}

	private static final class GroupConfig {
		private double eta;
		private double lambda;
		private double rhoHome;
		private double rhoAway;
		private final EnumMap<TimeBand, Double> timePreferences = new EnumMap<>(TimeBand.class);
		private final EnumMap<FutureChargingSupplyType, Double> supplyPreferences = new EnumMap<>(
				FutureChargingSupplyType.class);

		GroupConfig(double eta, double lambda) {
			this.eta = eta;
			this.lambda = lambda;
			for (TimeBand band : TimeBand.values()) {
				timePreferences.put(band, 0.0);
			}
			for (FutureChargingSupplyType type : FutureChargingSupplyType.values()) {
				supplyPreferences.put(type, 0.0);
			}
			supplyPreferences.put(FutureChargingSupplyType.FAST, 0.0);
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

		void setRhoHome(double rhoHome) {
			this.rhoHome = rhoHome;
		}

		void setRhoAway(double rhoAway) {
			this.rhoAway = rhoAway;
		}

		double getRhoHome() {
			return rhoHome;
		}

		double getRhoAway() {
			return rhoAway;
		}

		double getTimePreference(TimeBand band) {
			return timePreferences.getOrDefault(band, 0.0);
		}

		void setTimePreference(TimeBand band, double value) {
			timePreferences.put(band, value);
		}

		double getSupplyPreference(FutureChargingSupplyType type) {
			return supplyPreferences.getOrDefault(type, 0.0);
		}

		void setSupplyPreference(FutureChargingSupplyType type, double alpha) {
			supplyPreferences.put(type, alpha);
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
			return summary.population() == 0 ? Double.NaN : clip01((double) summary.chargedVehicles() / summary.population());
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

	private static final class LatentDemandDiagnostics {
		private final Set<Object> population = new HashSet<>();
		private final Set<Object> nonHomeCandidate = new HashSet<>();
		private final Set<Object> eligibleNonHome = new HashSet<>();
		private final Set<Object> preferredSelected = new HashSet<>();
		private final Set<Object> preferredReached = new HashSet<>();
		private final Set<Object> decisionEvaluated = new HashSet<>();
		private final Set<Object> demandGenerated = new HashSet<>();
		private final Set<Object> mandatoryDemand = new HashSet<>();
		private final Set<Object> conditionalDemand = new HashSet<>();
		private final Set<Object> socUpdated = new HashSet<>();

		private void reset() {
			population.clear();
			nonHomeCandidate.clear();
			eligibleNonHome.clear();
			preferredSelected.clear();
			preferredReached.clear();
			decisionEvaluated.clear();
			demandGenerated.clear();
			mandatoryDemand.clear();
			conditionalDemand.clear();
			socUpdated.clear();
		}
	}

	public record CalibrationEntry(int iteration, GroupType group, double pRealTotal, double pSimTotal, double alpha,
			double qTarget, double qSim, double learningRate, double delta, double etaBefore, double etaAfter,
			boolean updated) {
	}

	public record LatentDemandDiagnosticEntry(int iteration, GroupType group, int population,
			int nonHomeCandidatePersons, int eligibleNonHomePersons, int preferredSelectedPersons,
			int preferredReachedPersons, int decisionEvaluatedPersons, int demandGeneratedPersons,
			int mandatoryDemandPersons, int conditionalDemandPersons, int socUpdatedPersons) {
	}

	public record LatentPublicDemandRecord(String personId, String vehicleId, GroupType group,
			FutureChargingActivityLabel activityLabel, String activityType, String linkId, double time, TimeBand timeBand,
			double soc, double probability, boolean mandatory, FutureChargingSupplyType supplyType,
			double opportunityValue, String demandType, boolean socUpdated, double demandDuration, double energyDemand,
			double chargingDuration, double chargedEnergy, double socAfterCharging) {
	}
}
