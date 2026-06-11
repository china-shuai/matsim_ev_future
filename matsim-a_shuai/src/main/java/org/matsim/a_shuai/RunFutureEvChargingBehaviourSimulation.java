package org.matsim.a_shuai;

import java.lang.reflect.Constructor;

import org.matsim.contrib.ev.strategic.StrategicChargingConfigGroup;
import org.matsim.contrib.ev.strategic.StrategicChargingUtils;
import org.matsim.contrib.ev.strategic.costs.DefaultChargingCostsParameters;
import org.matsim.contrib.ev.strategic.replanning.innovator.RandomChargingPlanInnovator;
import org.matsim.contrib.ev.strategic.replanning.StrategicChargingReplanningStrategy;
import org.matsim.contrib.ev.strategic.scoring.ChargingPlanScoringParameters;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Runs MATSim with the Paper 3 future H/W/D/F charging behaviour model.
 *
 * <p>This entry point intentionally installs {@code FutureChargingBehaviourModule}
 * instead of the existing {@code ChargingBehaviourModule}, so the current model remains
 * available for the earlier paper's supplementary experiments.
 */
public class RunFutureEvChargingBehaviourSimulation {
	public static void main(String[] args) {
		String configFile = args.length > 0 ? args[0] : "/Users/S4065267/Matsim_ev/Simulation/config 0.1.xml";
		String outputDirectory = args.length > 1 ? args[1] : null;

		Config config = ConfigUtils.loadConfig(configFile,
				createConfigGroup("org.matsim.contrib.ev.EvConfigGroup"),
				createConfigGroup("org.matsim.contrib.ev.withinday.WithinDayEvConfigGroup"),
				createConfigGroup("org.matsim.contrib.ev.behavior.ChargingBehaviourConfigGroup"),
				createConfigGroup("org.matsim.contrib.ev.behavior.FutureChargingBehaviourConfigGroup"),
				createConfigGroup("org.matsim.contrib.ev.strategic.StrategicChargingConfigGroup"));
		if (outputDirectory != null && !outputDirectory.isBlank()) {
			config.controller().setOutputDirectory(outputDirectory);
		}

		StrategicChargingConfigGroup strategicConfig = StrategicChargingConfigGroup.get(config, true);
		if (strategicConfig.getInnovationParameters() == null) {
			RandomChargingPlanInnovator.Parameters params = new RandomChargingPlanInnovator.Parameters();
			params.setActivityInclusionProbability(1.0);
			params.setLegInclusionProbability(1.0);
			strategicConfig.addParameterSet(params);
		}
		if (strategicConfig.getCostParameters() == null) {
			strategicConfig.addParameterSet(new DefaultChargingCostsParameters());
		}
		if (strategicConfig.getScoringParameters() == null) {
			strategicConfig.addParameterSet(new ChargingPlanScoringParameters());
		}

		if (!hasStrategicChargingStrategy(config)) {
			StrategicChargingUtils.configureDefaultReplanning(config, 1.0);
		}

		Controler controler = new Controler(ScenarioUtils.loadScenario(config));
		addModule(controler, "org.matsim.contrib.ev.EvModule");
		addModule(controler, "org.matsim.contrib.ev.withinday.WithinDayEvModule");
		addModule(controler, "org.matsim.contrib.ev.behavior.FutureChargingBehaviourModule");
		addModule(controler, "org.matsim.contrib.ev.strategic.StrategicChargingModule");

		controler.run();
	}

	private static ConfigGroup createConfigGroup(String className) {
		try {
			Class<?> clazz = Class.forName(className);
			if (!ConfigGroup.class.isAssignableFrom(clazz)) {
				throw new IllegalArgumentException(className + " does not extend ConfigGroup");
			}
			Constructor<?> constructor = clazz.getDeclaredConstructor();
			constructor.setAccessible(true);
			return (ConfigGroup) constructor.newInstance();
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("Failed to instantiate config group " + className, e);
		}
	}

	private static void addModule(Controler controler, String moduleClassName) {
		try {
			Class<?> clazz = Class.forName(moduleClassName);
			if (!org.matsim.core.controler.AbstractModule.class.isAssignableFrom(clazz)) {
				throw new IllegalArgumentException(moduleClassName + " does not extend AbstractModule");
			}
			Constructor<?> constructor = clazz.getDeclaredConstructor();
			constructor.setAccessible(true);
			controler.addOverridingModule((org.matsim.core.controler.AbstractModule) constructor.newInstance());
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("Failed to instantiate module " + moduleClassName, e);
		}
	}

	private static boolean hasStrategicChargingStrategy(Config config) {
		for (StrategySettings settings : config.replanning().getStrategySettings()) {
			if (StrategicChargingReplanningStrategy.STRATEGY.equals(settings.getStrategyName())) {
				return true;
			}
		}
		return false;
	}
}
