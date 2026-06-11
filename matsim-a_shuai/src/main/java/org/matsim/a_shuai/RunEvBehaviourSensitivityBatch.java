package org.matsim.a_shuai;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.List;

import org.matsim.contrib.ev.behavior.ChargingBehaviourConfigGroup;
import org.matsim.contrib.ev.behavior.GroupType;
import org.matsim.contrib.ev.strategic.StrategicChargingConfigGroup;
import org.matsim.contrib.ev.strategic.StrategicChargingUtils;
import org.matsim.contrib.ev.strategic.costs.DefaultChargingCostsParameters;
import org.matsim.contrib.ev.strategic.replanning.innovator.RandomChargingPlanInnovator;
import org.matsim.contrib.ev.strategic.scoring.ChargingPlanScoringParameters;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * 一次性批量运行三组敏感性仿真：基准、低频（0.9 倍）、高频（1.1 倍）。
 *
 * <p>用法：
 * <pre>
 *   java org.matsim.a_shuai.RunEvBehaviourSensitivityBatch /path/to/config.xml [/base/output/dir]
 * </pre>
 *
 * <ul>
 *   <li>若不给定第二个参数，输出目录将在原配置的基础上附加场景后缀。</li>
 *   <li>每个场景会自动设置不同的 runId（baseline、freqLow、freqHigh）。</li>
 * </ul>
 */
public final class RunEvBehaviourSensitivityBatch {

	private record ScenarioSpec(String id, double multiplier) {
	}

	private static final List<ScenarioSpec> SCENARIOS = List.of(new ScenarioSpec("baseline", 1.0),
			new ScenarioSpec("freqLow", 0.9), new ScenarioSpec("freqHigh", 1.1));

	private RunEvBehaviourSensitivityBatch() {
	}

	public static void main(String[] args) {
		String configFile = args.length > 0 ? args[0] : "/Users/S4065267/Matsim_ev/Simulation/config 0.1.xml";
		String explicitBaseOutput = args.length > 1 ? args[1] : null;

		for (ScenarioSpec scenario : SCENARIOS) {
			Config config = loadConfig(configFile);
			applyFrequencyScaling(config, scenario.multiplier());
			config.controller().setRunId(scenario.id());
			config.controller().setOutputDirectory(resolveOutputDirectory(config, explicitBaseOutput, scenario.id()));

			StrategicChargingUtils.configureDefaultReplanning(config, 1.0);

			Controler controler = new Controler(ScenarioUtils.loadScenario(config));
			addModule(controler, "org.matsim.contrib.ev.EvModule");
			addModule(controler, "org.matsim.contrib.ev.withinday.WithinDayEvModule");
			addModule(controler, "org.matsim.contrib.ev.behavior.ChargingBehaviourModule");
			addModule(controler, "org.matsim.contrib.ev.strategic.StrategicChargingModule");

			controler.run();
		}
	}

	private static Config loadConfig(String configFile) {
		Config config = ConfigUtils.loadConfig(configFile, createConfigGroup("org.matsim.contrib.ev.EvConfigGroup"),
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

		return config;
	}

	private static void applyFrequencyScaling(Config config, double multiplier) {
		ChargingBehaviourConfigGroup behaviour = ConfigUtils.addOrGetModule(config, ChargingBehaviourConfigGroup.class);
		for (GroupType group : GroupType.values()) {
			double scaled = clip01(behaviour.getRealFrequency(group) * multiplier);
			switch (group) {
				case APARTMENT -> behaviour.setRealFrequencyApartment(scaled);
				case HOUSE_WITH_PV -> behaviour.setRealFrequencyHouseWithPv(scaled);
				case HOUSE_NO_PV -> behaviour.setRealFrequencyHouseNoPv(scaled);
				default -> throw new IllegalStateException("未知的 GroupType: " + group);
			}
		}
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

	private static String resolveOutputDirectory(Config config, String explicitBase, String scenarioId) {
		if (explicitBase != null && !explicitBase.isBlank()) {
			return Path.of(explicitBase, scenarioId).toString();
		}
		String original = config.controller().getOutputDirectory();
		if (original == null || original.isBlank()) {
			Path base = Path.of("output");
			return base.resolve(scenarioId).toString();
		}
		return original.endsWith("/") ? original + scenarioId : original + "_" + scenarioId;
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
			throw new RuntimeException("无法实例化配置组: " + className, e);
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
			throw new RuntimeException("无法实例化模块: " + moduleClassName, e);
		}
	}
}


