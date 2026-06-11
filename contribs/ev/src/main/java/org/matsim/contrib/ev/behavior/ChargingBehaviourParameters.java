package org.matsim.contrib.ev.behavior;

import java.util.EnumMap;
import java.util.Map;

/**
 * Container for all configurable parameters used by {@link ChargingBehaviourModel}.
 */
public final class ChargingBehaviourParameters {

	private double learningRate;
	private double acReferencePower;
	private double dcfcReferencePower;
	private double learningRateDecay;
	private final EnumMap<GroupType, GroupParameters> groupParameters = new EnumMap<>(GroupType.class);

	private ChargingBehaviourParameters() {
	}

	public static ChargingBehaviourParameters createDefault() {
		ChargingBehaviourParameters parameters = new ChargingBehaviourParameters();
		parameters.learningRate = 0.2;
		parameters.acReferencePower = 50.0;
		parameters.dcfcReferencePower = 200.0;
		parameters.learningRateDecay = 0.0;

		for (GroupType group : GroupType.values()) {
			GroupParameters groupParameters = new GroupParameters();
			groupParameters.lambda = 1.0;
			groupParameters.rhoHome = 0.0;
			groupParameters.rhoAway = 0.0;

			for (TimeBand band : TimeBand.values()) {
				groupParameters.timePreferences.put(band, 0.0);
			}

			switch (group) {
			case APARTMENT:
				groupParameters.realFrequency = 0.4598;
				groupParameters.timePreferences.put(TimeBand.MORNING_6_10, -1.665);
				groupParameters.timePreferences.put(TimeBand.MIDDAY_10_16, -1.046);
				groupParameters.timePreferences.put(TimeBand.EVENING_16_22, 0.0);
				groupParameters.timePreferences.put(TimeBand.NIGHT_22_6, -1.213);
				break;
			case HOUSE_WITH_PV:
				groupParameters.realFrequency = 0.6071;
				groupParameters.timePreferences.put(TimeBand.MORNING_6_10, -1.266);
				groupParameters.timePreferences.put(TimeBand.MIDDAY_10_16, -2.565);
				groupParameters.timePreferences.put(TimeBand.EVENING_16_22, 0.0);
				groupParameters.timePreferences.put(TimeBand.NIGHT_22_6, -0.956);
				break;
			case HOUSE_NO_PV:
				groupParameters.realFrequency = 0.3277;
				groupParameters.timePreferences.put(TimeBand.MORNING_6_10, -0.298);
				groupParameters.timePreferences.put(TimeBand.MIDDAY_10_16, -1.825);
				groupParameters.timePreferences.put(TimeBand.EVENING_16_22, 0.0);
				groupParameters.timePreferences.put(TimeBand.NIGHT_22_6, -0.869);
				break;
			default:
				throw new IllegalStateException("Unexpected value: " + group);
			}

			parameters.groupParameters.put(group, groupParameters);
		}

		return parameters;
	}

	public double getLearningRate() {
		return learningRate;
	}

	public void setLearningRate(double learningRate) {
		this.learningRate = learningRate;
	}

	public double getAcReferencePower() {
		return acReferencePower;
	}

	public void setAcReferencePower(double acReferencePower) {
		this.acReferencePower = acReferencePower;
	}

	public double getDcfcReferencePower() {
		return dcfcReferencePower;
	}

	public void setDcfcReferencePower(double dcfcReferencePower) {
		this.dcfcReferencePower = dcfcReferencePower;
	}

	public double getLearningRateDecay() {
		return learningRateDecay;
	}

	public void setLearningRateDecay(double learningRateDecay) {
		this.learningRateDecay = learningRateDecay;
	}

	public GroupParameters getGroupParameters(GroupType group) {
		return groupParameters.get(group);
	}

	public Map<GroupType, GroupParameters> getGroupParameters() {
		return groupParameters;
	}

	public static final class GroupParameters {
		private double realFrequency;
		private double lambda;
		private double rhoHome;
		private double rhoAway;
		private final EnumMap<TimeBand, Double> timePreferences = new EnumMap<>(TimeBand.class);

		private GroupParameters() {
		}

		public double getRealFrequency() {
			return realFrequency;
		}

		public void setRealFrequency(double realFrequency) {
			this.realFrequency = realFrequency;
		}

		public double getLambda() {
			return lambda;
		}

		public void setLambda(double lambda) {
			this.lambda = lambda;
		}

		public double getRhoHome() {
			return rhoHome;
		}

		public void setRhoHome(double rhoHome) {
			this.rhoHome = rhoHome;
		}

		public double getRhoAway() {
			return rhoAway;
		}

		public void setRhoAway(double rhoAway) {
			this.rhoAway = rhoAway;
		}

		public void setTimePreference(TimeBand band, double value) {
			timePreferences.put(band, value);
		}

		public double getTimePreference(TimeBand band) {
			return timePreferences.getOrDefault(band, 0.0);
		}

		public Map<TimeBand, Double> getTimePreferences() {
			return timePreferences;
		}
	}
}

