package org.matsim.a_shuai;

import java.lang.reflect.Constructor;

import org.matsim.contrib.ev.strategic.StrategicChargingConfigGroup;
import org.matsim.contrib.ev.strategic.StrategicChargingUtils;
import org.matsim.contrib.ev.strategic.costs.DefaultChargingCostsParameters;
import org.matsim.contrib.ev.strategic.scoring.ChargingPlanScoringParameters;
import org.matsim.contrib.ev.strategic.replanning.innovator.RandomChargingPlanInnovator;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Convenience entry point for running MATSim with the customised EV charging behaviour.
 *
 * <p>Usage:
 * <pre>
 *   java org.matsim.a_shuai.RunEvBehaviourSimulation path/to/config.xml [outputDirectory]
 * </pre>
 *
 * The configuration should include the {@code chargingBehaviour} module to supply
 * the behavioural parameters and optional CSV inputs.
 */
public class RunEvBehaviourSimulation {

	public static void main(String[] args) {
		// if (args.length == 0) {
		// 	System.err.println("Usage: RunEvBehaviourSimulation <config.xml> [outputDirectory]");
		// 	System.exit(1);
		// }

		String configFile = "/Users/S4065267/Matsim_ev/Simulation/config_HC90.xml";

		Config config = ConfigUtils.loadConfig(configFile,
				createConfigGroup("org.matsim.contrib.ev.EvConfigGroup"),
				createConfigGroup("org.matsim.contrib.ev.withinday.WithinDayEvConfigGroup"),
				createConfigGroup("org.matsim.contrib.ev.behavior.ChargingBehaviourConfigGroup"),
				createConfigGroup("org.matsim.contrib.ev.strategic.StrategicChargingConfigGroup"));

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

		StrategicChargingUtils.configureDefaultReplanning(config, 1.0);

		var scenario = ScenarioUtils.loadScenario(config);
		Controler controler = new Controler(scenario);

		addModule(controler, "org.matsim.contrib.ev.EvModule");
		addModule(controler, "org.matsim.contrib.ev.withinday.WithinDayEvModule");
		addModule(controler, "org.matsim.contrib.ev.behavior.ChargingBehaviourModule");
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
}

