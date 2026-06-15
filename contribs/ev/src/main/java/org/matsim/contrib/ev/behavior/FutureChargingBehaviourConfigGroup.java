package org.matsim.contrib.ev.behavior;

import java.net.URL;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;

import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * Config group for the Paper 3 sequential H/W/D/F charging model.
 */
public class FutureChargingBehaviourConfigGroup extends ReflectiveConfigGroup {
	public static final String GROUP_NAME = "futureChargingBehaviour";
	public static final String DEFAULT_DESTINATION_CHARGING_ACTIVITY_TYPES = "Shop,Social/Recreational,Personal";

	private final EnumMap<GroupType, Double> lambdas = new EnumMap<>(GroupType.class);
	private final EnumMap<GroupType, Double> rhoHome = new EnumMap<>(GroupType.class);
	private final EnumMap<GroupType, Double> rhoAway = new EnumMap<>(GroupType.class);
	private final EnumMap<GroupType, Double> realFrequencies = new EnumMap<>(GroupType.class);

	private double learningRate = 0.2;
	private double learningRateDecay = 0.0;
	private int startCalibrationIteration = 0;
	private double minimumSoc = 0.2;
	private double thetaOmega = 1.0;
	private double activitySelectionTemperature = 0.0;
	private double acReferencePowerKw = 22.0;
	private double dcfcReferencePowerKw = 250.0;
	private boolean workplaceChargingAvailable = false;
	private boolean destinationChargingAvailable = false;
	private String destinationChargingActivityTypes = DEFAULT_DESTINATION_CHARGING_ACTIVITY_TYPES;
	private boolean publicFastFallback = true;
	private boolean latentPublicDemand = false;
	private boolean unrestrictedLatentPublicDemand = false;
	private double latentWorkplaceServiceRate = 0.0;
	private double latentDestinationServiceRate = 0.0;
	private double minimumLatentActivityChargingDuration = 1800.0;
	private double minimumLatentFastChargingDuration = 900.0;
	private double maximumLatentPublicFastSoc = 1.0;
	private double publicStartSocWeightExponent = 0.0;
	private String latentPublicDemandFile = "futureChargingLatentPublicDemand.csv";
	private String timePreferenceFile;
	private String locationPreferenceFile;
	private String destinationPreferenceFile;
	private String supplyPreferenceFile;
	private String publicStartSocDistributionFiles;
	private String latentPublicFastCandidateFile;
	private double latentPublicFastSearchRadius = Double.POSITIVE_INFINITY;

	public FutureChargingBehaviourConfigGroup() {
		super(GROUP_NAME);

		FutureChargingBehaviourParameters defaults = FutureChargingBehaviourParameters.createDefault();
		for (GroupType group : GroupType.values()) {
			FutureChargingBehaviourParameters.GroupParameters groupDefaults = defaults.getGroupParameters(group);
			lambdas.put(group, groupDefaults.getLambda());
			rhoHome.put(group, groupDefaults.getRhoHome());
			rhoAway.put(group, groupDefaults.getRhoAway());
			realFrequencies.put(group, groupDefaults.getRealFrequency());
		}
	}

	@StringGetter("learningRate")
	public double getLearningRate() {
		return learningRate;
	}

	@StringSetter("learningRate")
	public void setLearningRate(double learningRate) {
		this.learningRate = Math.max(0.0, learningRate);
	}

	@StringGetter("learningRateDecay")
	public double getLearningRateDecay() {
		return learningRateDecay;
	}

	@StringSetter("learningRateDecay")
	public void setLearningRateDecay(double learningRateDecay) {
		this.learningRateDecay = Math.max(0.0, learningRateDecay);
	}

	@StringGetter("startCalibrationIteration")
	public int getStartCalibrationIteration() {
		return startCalibrationIteration;
	}

	@StringSetter("startCalibrationIteration")
	public void setStartCalibrationIteration(int startCalibrationIteration) {
		this.startCalibrationIteration = Math.max(0, startCalibrationIteration);
	}

	@StringGetter("minimumSoc")
	public double getMinimumSoc() {
		return minimumSoc;
	}

	@StringSetter("minimumSoc")
	public void setMinimumSoc(double minimumSoc) {
		this.minimumSoc = clip01(minimumSoc);
	}

	@StringGetter("thetaOmega")
	public double getThetaOmega() {
		return thetaOmega;
	}

	@StringSetter("thetaOmega")
	public void setThetaOmega(double thetaOmega) {
		this.thetaOmega = thetaOmega;
	}

	@StringGetter("activitySelectionTemperature")
	public double getActivitySelectionTemperature() {
		return activitySelectionTemperature;
	}

	@StringSetter("activitySelectionTemperature")
	public void setActivitySelectionTemperature(double activitySelectionTemperature) {
		this.activitySelectionTemperature = Math.max(0.0, activitySelectionTemperature);
	}

	public double getAcReferencePower() {
		return acReferencePowerKw * 1000.0;
	}

	@StringSetter("acReferencePower")
	public void setAcReferencePower(double acReferencePower) {
		this.acReferencePowerKw = acReferencePower / 1000.0;
	}

	public double getDcfcReferencePower() {
		return dcfcReferencePowerKw * 1000.0;
	}

	@StringSetter("dcfcReferencePower")
	public void setDcfcReferencePower(double dcfcReferencePower) {
		this.dcfcReferencePowerKw = dcfcReferencePower / 1000.0;
	}

	@StringGetter("acReferencePowerKw")
	public double getAcReferencePowerKw() {
		return acReferencePowerKw;
	}

	@StringSetter("acReferencePowerKw")
	public void setAcReferencePowerKw(double acReferencePowerKw) {
		this.acReferencePowerKw = acReferencePowerKw;
	}

	@StringGetter("dcfcReferencePowerKw")
	public double getDcfcReferencePowerKw() {
		return dcfcReferencePowerKw;
	}

	@StringSetter("dcfcReferencePowerKw")
	public void setDcfcReferencePowerKw(double dcfcReferencePowerKw) {
		this.dcfcReferencePowerKw = dcfcReferencePowerKw;
	}

	@StringGetter("workplaceChargingAvailable")
	public boolean isWorkplaceChargingAvailable() {
		return workplaceChargingAvailable;
	}

	@StringSetter("workplaceChargingAvailable")
	public void setWorkplaceChargingAvailable(boolean workplaceChargingAvailable) {
		this.workplaceChargingAvailable = workplaceChargingAvailable;
	}

	@StringGetter("destinationChargingAvailable")
	public boolean isDestinationChargingAvailable() {
		return destinationChargingAvailable;
	}

	@StringSetter("destinationChargingAvailable")
	public void setDestinationChargingAvailable(boolean destinationChargingAvailable) {
		this.destinationChargingAvailable = destinationChargingAvailable;
	}

	@StringGetter("destinationChargingActivityTypes")
	public String getDestinationChargingActivityTypes() {
		return destinationChargingActivityTypes;
	}

	@StringSetter("destinationChargingActivityTypes")
	public void setDestinationChargingActivityTypes(String destinationChargingActivityTypes) {
		this.destinationChargingActivityTypes = destinationChargingActivityTypes == null ? ""
				: destinationChargingActivityTypes.trim();
	}

	public EnumSet<FutureChargingActivityLabel> getDestinationChargingActivityLabels() {
		EnumSet<FutureChargingActivityLabel> labels = EnumSet.noneOf(FutureChargingActivityLabel.class);
		if (destinationChargingActivityTypes == null || destinationChargingActivityTypes.isBlank()) {
			return labels;
		}
		for (String token : destinationChargingActivityTypes.split("[,;|]")) {
			if (!token.isBlank()) {
				labels.add(FutureChargingActivityLabel.fromActivityType(token.trim()));
			}
		}
		return labels;
	}

	@StringGetter("publicFastFallback")
	public boolean isPublicFastFallback() {
		return publicFastFallback;
	}

	@StringSetter("publicFastFallback")
	public void setPublicFastFallback(boolean publicFastFallback) {
		this.publicFastFallback = publicFastFallback;
	}

	@StringGetter("latentPublicDemand")
	public boolean isLatentPublicDemand() {
		return latentPublicDemand;
	}

	@StringSetter("latentPublicDemand")
	public void setLatentPublicDemand(boolean latentPublicDemand) {
		this.latentPublicDemand = latentPublicDemand;
	}

	@StringGetter("unrestrictedLatentPublicDemand")
	public boolean isUnrestrictedLatentPublicDemand() {
		return unrestrictedLatentPublicDemand;
	}

	@StringSetter("unrestrictedLatentPublicDemand")
	public void setUnrestrictedLatentPublicDemand(boolean unrestrictedLatentPublicDemand) {
		this.unrestrictedLatentPublicDemand = unrestrictedLatentPublicDemand;
	}

	@StringGetter("latentWorkplaceServiceRate")
	public double getLatentWorkplaceServiceRate() {
		return latentWorkplaceServiceRate;
	}

	@StringSetter("latentWorkplaceServiceRate")
	public void setLatentWorkplaceServiceRate(double latentWorkplaceServiceRate) {
		this.latentWorkplaceServiceRate = clip01(latentWorkplaceServiceRate);
	}

	@StringGetter("latentDestinationServiceRate")
	public double getLatentDestinationServiceRate() {
		return latentDestinationServiceRate;
	}

	@StringSetter("latentDestinationServiceRate")
	public void setLatentDestinationServiceRate(double latentDestinationServiceRate) {
		this.latentDestinationServiceRate = clip01(latentDestinationServiceRate);
	}

	@StringGetter("minimumLatentActivityChargingDuration")
	public double getMinimumLatentActivityChargingDuration() {
		return minimumLatentActivityChargingDuration;
	}

	@StringSetter("minimumLatentActivityChargingDuration")
	public void setMinimumLatentActivityChargingDuration(double minimumLatentActivityChargingDuration) {
		this.minimumLatentActivityChargingDuration = nonNegativeOrInfinity(minimumLatentActivityChargingDuration);
	}

	@StringGetter("minimumLatentFastChargingDuration")
	public double getMinimumLatentFastChargingDuration() {
		return minimumLatentFastChargingDuration;
	}

	@StringSetter("minimumLatentFastChargingDuration")
	public void setMinimumLatentFastChargingDuration(double minimumLatentFastChargingDuration) {
		this.minimumLatentFastChargingDuration = nonNegativeOrInfinity(minimumLatentFastChargingDuration);
	}

	@StringGetter("maximumLatentPublicFastSoc")
	public double getMaximumLatentPublicFastSoc() {
		return maximumLatentPublicFastSoc;
	}

	@StringSetter("maximumLatentPublicFastSoc")
	public void setMaximumLatentPublicFastSoc(double maximumLatentPublicFastSoc) {
		this.maximumLatentPublicFastSoc = clip01(maximumLatentPublicFastSoc);
	}

	@StringGetter("publicStartSocWeightExponent")
	public double getPublicStartSocWeightExponent() {
		return publicStartSocWeightExponent;
	}

	@StringSetter("publicStartSocWeightExponent")
	public void setPublicStartSocWeightExponent(double publicStartSocWeightExponent) {
		this.publicStartSocWeightExponent = Math.max(0.0, publicStartSocWeightExponent);
	}

	@StringGetter("latentPublicDemandFile")
	public String getLatentPublicDemandFile() {
		return latentPublicDemandFile;
	}

	@StringSetter("latentPublicDemandFile")
	public void setLatentPublicDemandFile(String latentPublicDemandFile) {
		this.latentPublicDemandFile = latentPublicDemandFile;
	}

	@StringGetter("timePreferenceFile")
	public String getTimePreferenceFile() {
		return timePreferenceFile;
	}

	@StringSetter("timePreferenceFile")
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

	@StringGetter("supplyPreferenceFile")
	public String getSupplyPreferenceFile() {
		return supplyPreferenceFile;
	}

	@StringSetter("supplyPreferenceFile")
	public void setSupplyPreferenceFile(String supplyPreferenceFile) {
		this.supplyPreferenceFile = supplyPreferenceFile;
	}

	public URL getSupplyPreferenceFileURL(URL context) {
		return supplyPreferenceFile == null ? null : getInputFileURL(context, supplyPreferenceFile);
	}

	@StringGetter("publicStartSocDistributionFiles")
	public String getPublicStartSocDistributionFiles() {
		return publicStartSocDistributionFiles;
	}

	@StringSetter("publicStartSocDistributionFiles")
	public void setPublicStartSocDistributionFiles(String publicStartSocDistributionFiles) {
		this.publicStartSocDistributionFiles = publicStartSocDistributionFiles;
	}

	public List<URL> getPublicStartSocDistributionFileURLs(URL context) {
		List<URL> urls = new ArrayList<>();
		if (publicStartSocDistributionFiles == null || publicStartSocDistributionFiles.isBlank()) {
			return urls;
		}
		for (String file : publicStartSocDistributionFiles.split("\\|")) {
			if (!file.isBlank()) {
				urls.add(getInputFileURL(context, file.trim()));
			}
		}
		return urls;
	}

	@StringGetter("latentPublicFastCandidateFile")
	public String getLatentPublicFastCandidateFile() {
		return latentPublicFastCandidateFile;
	}

	@StringSetter("latentPublicFastCandidateFile")
	public void setLatentPublicFastCandidateFile(String latentPublicFastCandidateFile) {
		this.latentPublicFastCandidateFile = latentPublicFastCandidateFile;
	}

	public URL getLatentPublicFastCandidateFileURL(URL context) {
		return latentPublicFastCandidateFile == null || latentPublicFastCandidateFile.isBlank() ? null
				: getInputFileURL(context, latentPublicFastCandidateFile);
	}

	@StringGetter("latentPublicFastSearchRadius")
	public double getLatentPublicFastSearchRadius() {
		return latentPublicFastSearchRadius;
	}

	@StringSetter("latentPublicFastSearchRadius")
	public void setLatentPublicFastSearchRadius(double latentPublicFastSearchRadius) {
		this.latentPublicFastSearchRadius = nonNegativeOrInfinity(latentPublicFastSearchRadius);
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

	@StringGetter("realFrequencyApartment")
	public double getRealFrequencyApartment() {
		return getRealFrequency(GroupType.APARTMENT);
	}

	@StringSetter("realFrequencyApartment")
	public void setRealFrequencyApartment(double value) {
		realFrequencies.put(GroupType.APARTMENT, clip01(value));
	}

	@StringGetter("realFrequencyHouseWithPv")
	public double getRealFrequencyHouseWithPv() {
		return getRealFrequency(GroupType.HOUSE_WITH_PV);
	}

	@StringSetter("realFrequencyHouseWithPv")
	public void setRealFrequencyHouseWithPv(double value) {
		realFrequencies.put(GroupType.HOUSE_WITH_PV, clip01(value));
	}

	@StringGetter("realFrequencyHouseNoPv")
	public double getRealFrequencyHouseNoPv() {
		return getRealFrequency(GroupType.HOUSE_NO_PV);
	}

	@StringSetter("realFrequencyHouseNoPv")
	public void setRealFrequencyHouseNoPv(double value) {
		realFrequencies.put(GroupType.HOUSE_NO_PV, clip01(value));
	}

	private static double clip01(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	private static double nonNegativeOrInfinity(double value) {
		if (Double.isNaN(value)) {
			return 0.0;
		}
		return value == Double.POSITIVE_INFINITY ? value : Math.max(0.0, value);
	}
}
