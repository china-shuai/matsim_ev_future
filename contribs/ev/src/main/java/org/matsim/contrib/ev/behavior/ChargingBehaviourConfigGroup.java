package org.matsim.contrib.ev.behavior;

import java.net.URL;
import java.util.EnumMap;

import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * Config group allowing to customise {@link ChargingBehaviourModel}.
 */
public class ChargingBehaviourConfigGroup extends ReflectiveConfigGroup {
	public static final String GROUP_NAME = "chargingBehaviour";

	private static final String LEARNING_RATE = "learningRate";
	private static final String TIME_PREFERENCE_FILE = "timePreferenceFile";
	private static final String LEARNING_RATE_DECAY = "learningRateDecay";

	private final EnumMap<GroupType, Double> lambdas = new EnumMap<>(GroupType.class);
	private final EnumMap<GroupType, Double> rhoHome = new EnumMap<>(GroupType.class);
	private final EnumMap<GroupType, Double> rhoAway = new EnumMap<>(GroupType.class);
	private final EnumMap<GroupType, Double> realFrequencies = new EnumMap<>(GroupType.class);

	private double learningRate = 0.2;
	private String timePreferenceFile;
	private String locationPreferenceFile;
	private String destinationPreferenceFile;
	private double acReferencePower = 50.0;
	private double dcfcReferencePower = 200.0;
	private int startCalibrationIteration = 0;
	private double learningRateDecay = 0.0;

	public ChargingBehaviourConfigGroup() {
		super(GROUP_NAME);

		for (GroupType group : GroupType.values()) {
			lambdas.put(group, 1.0);
			rhoHome.put(group, 0.0);
			rhoAway.put(group, 0.0);
		}

		realFrequencies.put(GroupType.APARTMENT, 0.4598);
		realFrequencies.put(GroupType.HOUSE_WITH_PV, 0.6071);
		realFrequencies.put(GroupType.HOUSE_NO_PV, 0.3277);
	}

	@StringGetter("startCalibrationIteration")
	public int getStartCalibrationIteration() {
		return startCalibrationIteration;
	}

	@StringSetter("startCalibrationIteration")
	public void setStartCalibrationIteration(int startCalibrationIteration) {
		this.startCalibrationIteration = Math.max(0, startCalibrationIteration);
	}

	@StringGetter(LEARNING_RATE)
	public double getLearningRate() {
		return learningRate;
	}

	@StringSetter(LEARNING_RATE)
	public void setLearningRate(double learningRate) {
		this.learningRate = learningRate;
	}

	@StringGetter(LEARNING_RATE_DECAY)
	public double getLearningRateDecay() {
		return learningRateDecay;
	}

	@StringSetter(LEARNING_RATE_DECAY)
	public void setLearningRateDecay(double learningRateDecay) {
		this.learningRateDecay = Math.max(0.0, learningRateDecay);
	}

	@StringGetter(TIME_PREFERENCE_FILE)
	public String getTimePreferenceFile() {
		return timePreferenceFile;
	}

	@StringSetter(TIME_PREFERENCE_FILE)
	public void setTimePreferenceFile(String timePreferenceFile) {
		this.timePreferenceFile = timePreferenceFile;
	}

	public URL getTimePreferenceFileURL(URL context) {
		return timePreferenceFile == null ? null : getInputFileURL(context, timePreferenceFile);
	}

	@StringGetter("locationPreferenceFile")
	public String getLocationPreferenceFile() {
		return locationPreferenceFile;
	}

	@StringSetter("locationPreferenceFile")
	public void setLocationPreferenceFile(String locationPreferenceFile) {
		this.locationPreferenceFile = locationPreferenceFile;
	}

	public URL getLocationPreferenceFileURL(URL context) {
		return locationPreferenceFile == null ? null : getInputFileURL(context, locationPreferenceFile);
	}

	@StringGetter("destinationPreferenceFile")
	public String getDestinationPreferenceFile() {
		return destinationPreferenceFile;
	}

	@StringSetter("destinationPreferenceFile")
	public void setDestinationPreferenceFile(String destinationPreferenceFile) {
		this.destinationPreferenceFile = destinationPreferenceFile;
	}

	public URL getDestinationPreferenceFileURL(URL context) {
		return destinationPreferenceFile == null ? null : getInputFileURL(context, destinationPreferenceFile);
	}

	@StringGetter("acReferencePower")
	public double getAcReferencePower() {
		return acReferencePower;
	}

	@StringSetter("acReferencePower")
	public void setAcReferencePower(double acReferencePower) {
		this.acReferencePower = acReferencePower;
	}

	@StringGetter("dcfcReferencePower")
	public double getDcfcReferencePower() {
		return dcfcReferencePower;
	}

	@StringSetter("dcfcReferencePower")
	public void setDcfcReferencePower(double dcfcReferencePower) {
		this.dcfcReferencePower = dcfcReferencePower;
	}

	public double getLambda(GroupType group) {
		return lambdas.get(group);
	}

	public double getRhoHome(GroupType group) {
		return rhoHome.get(group);
	}

	public double getRhoAway(GroupType group) {
		return rhoAway.get(group);
	}

	public double getRealFrequency(GroupType group) {
		return realFrequencies.get(group);
	}

	@StringGetter("lambdaApartment")
	public double getLambdaApartment() {
		return getLambda(GroupType.APARTMENT);
	}

	@StringSetter("lambdaApartment")
	public void setLambdaApartment(double value) {
		lambdas.put(GroupType.APARTMENT, value);
	}

	@StringGetter("lambdaHouseWithPv")
	public double getLambdaHouseWithPv() {
		return getLambda(GroupType.HOUSE_WITH_PV);
	}

	@StringSetter("lambdaHouseWithPv")
	public void setLambdaHouseWithPv(double value) {
		lambdas.put(GroupType.HOUSE_WITH_PV, value);
	}

	@StringGetter("lambdaHouseNoPv")
	public double getLambdaHouseNoPv() {
		return getLambda(GroupType.HOUSE_NO_PV);
	}

	@StringSetter("lambdaHouseNoPv")
	public void setLambdaHouseNoPv(double value) {
		lambdas.put(GroupType.HOUSE_NO_PV, value);
	}

	@StringGetter("rhoHomeApartment")
	public double getRhoHomeApartment() {
		return getRhoHome(GroupType.APARTMENT);
	}

	@StringSetter("rhoHomeApartment")
	public void setRhoHomeApartment(double value) {
		rhoHome.put(GroupType.APARTMENT, value);
	}

	@StringGetter("rhoHomeHouseWithPv")
	public double getRhoHomeHouseWithPv() {
		return getRhoHome(GroupType.HOUSE_WITH_PV);
	}

	@StringSetter("rhoHomeHouseWithPv")
	public void setRhoHomeHouseWithPv(double value) {
		rhoHome.put(GroupType.HOUSE_WITH_PV, value);
	}

	@StringGetter("rhoHomeHouseNoPv")
	public double getRhoHomeHouseNoPv() {
		return getRhoHome(GroupType.HOUSE_NO_PV);
	}

	@StringSetter("rhoHomeHouseNoPv")
	public void setRhoHomeHouseNoPv(double value) {
		rhoHome.put(GroupType.HOUSE_NO_PV, value);
	}

	@StringGetter("rhoAwayApartment")
	public double getRhoAwayApartment() {
		return getRhoAway(GroupType.APARTMENT);
	}

	@StringSetter("rhoAwayApartment")
	public void setRhoAwayApartment(double value) {
		rhoAway.put(GroupType.APARTMENT, value);
	}

	@StringGetter("rhoAwayHouseWithPv")
	public double getRhoAwayHouseWithPv() {
		return getRhoAway(GroupType.HOUSE_WITH_PV);
	}

	@StringSetter("rhoAwayHouseWithPv")
	public void setRhoAwayHouseWithPv(double value) {
		rhoAway.put(GroupType.HOUSE_WITH_PV, value);
	}

	@StringGetter("rhoAwayHouseNoPv")
	public double getRhoAwayHouseNoPv() {
		return getRhoAway(GroupType.HOUSE_NO_PV);
	}

	@StringSetter("rhoAwayHouseNoPv")
	public void setRhoAwayHouseNoPv(double value) {
		rhoAway.put(GroupType.HOUSE_NO_PV, value);
	}

	@StringGetter("realFrequencyApartment")
	public double getRealFrequencyApartment() {
		return getRealFrequency(GroupType.APARTMENT);
	}

	@StringSetter("realFrequencyApartment")
	public void setRealFrequencyApartment(double value) {
		realFrequencies.put(GroupType.APARTMENT, value);
	}

	@StringGetter("realFrequencyHouseWithPv")
	public double getRealFrequencyHouseWithPv() {
		return getRealFrequency(GroupType.HOUSE_WITH_PV);
	}

	@StringSetter("realFrequencyHouseWithPv")
	public void setRealFrequencyHouseWithPv(double value) {
		realFrequencies.put(GroupType.HOUSE_WITH_PV, value);
	}

	@StringGetter("realFrequencyHouseNoPv")
	public double getRealFrequencyHouseNoPv() {
		return getRealFrequency(GroupType.HOUSE_NO_PV);
	}

	@StringSetter("realFrequencyHouseNoPv")
	public void setRealFrequencyHouseNoPv(double value) {
		realFrequencies.put(GroupType.HOUSE_NO_PV, value);
	}
}

