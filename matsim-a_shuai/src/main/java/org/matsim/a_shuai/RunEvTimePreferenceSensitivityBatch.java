package org.matsim.a_shuai;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.matsim.contrib.ev.behavior.ChargingBehaviourConfigGroup;
import org.matsim.contrib.ev.behavior.ChargingBehaviourParameters;
import org.matsim.contrib.ev.behavior.GroupType;
import org.matsim.contrib.ev.behavior.TimeBand;
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
 * 批量运行两组时间偏好敏感性仿真：{@code γ = 0.5} 与 {@code γ = 0.0}。
 *
 * <p>用法：
 * <pre>
 *   java org.matsim.a_shuai.RunEvTimePreferenceSensitivityBatch /path/to/config.xml [/base/output/dir]
 * </pre>
 *
 * <ul>
 *   <li>基准 {@code γ = 1.0} 不在此处运行，仅生成其它两个场景。</li>
 *   <li>每个场景自动设置不同的 runId（{@code tau05}、{@code tau00}）。</li>
 * </ul>
 */
public final class RunEvTimePreferenceSensitivityBatch {

	private record ScenarioSpec(String id, double gamma) {
	}

	private static final List<ScenarioSpec> SCENARIOS = List.of(new ScenarioSpec("tau2.0", 2.0),
			new ScenarioSpec("tau4", 4));

	private RunEvTimePreferenceSensitivityBatch() {
	}

	public static void main(String[] args) {
		String configFile = args.length > 0 ? args[0] : "/Users/S4065267/Matsim_ev/Simulation/config 0.1.xml";
		String explicitBaseOutput = args.length > 1 ? args[1] : null;

		for (ScenarioSpec scenario : SCENARIOS) {
			Config config = loadConfig(configFile);
			applyTimePreferenceScaling(config, scenario.gamma());
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

	private static void applyTimePreferenceScaling(Config config, double gamma) {
		ChargingBehaviourConfigGroup behaviour = ConfigUtils.addOrGetModule(config,
				ChargingBehaviourConfigGroup.GROUP_NAME, ChargingBehaviourConfigGroup.class);

		EnumMap<GroupType, EnumMap<TimeBand, Double>> baseline = loadBaselineTimePreferences(config, behaviour);
		Path scaledFile = writeScaledTimePreferenceFile(baseline, gamma, behaviour.getTimePreferenceFile(),
				config.getContext());
		behaviour.setTimePreferenceFile(scaledFile.toAbsolutePath().toString());
	}

	private static EnumMap<GroupType, EnumMap<TimeBand, Double>> loadBaselineTimePreferences(Config config,
			ChargingBehaviourConfigGroup behaviour) {
		EnumMap<GroupType, EnumMap<TimeBand, Double>> values = new EnumMap<>(GroupType.class);
		ChargingBehaviourParameters defaults = ChargingBehaviourParameters.createDefault();

		for (GroupType group : GroupType.values()) {
			EnumMap<TimeBand, Double> map = new EnumMap<>(TimeBand.class);
			for (TimeBand band : TimeBand.values()) {
				map.put(band, defaults.getGroupParameters(group).getTimePreference(band));
			}
			values.put(group, map);
		}

		String file = behaviour.getTimePreferenceFile();
		if (file == null || file.isBlank()) {
			return values;
		}

		try (BufferedReader reader = new BufferedReader(
				new java.io.InputStreamReader(behaviour.getTimePreferenceFileURL(config.getContext()).openStream(),
						StandardCharsets.UTF_8))) {
			String line;
			boolean headerProcessed = false;

			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}

				if (!headerProcessed && line.toLowerCase(Locale.ROOT).contains("group")) {
					headerProcessed = true;
					continue;
				}
				headerProcessed = true;

				String[] tokens = line.split("[,;\\t]");
				if (tokens.length < 3) {
					throw new IllegalArgumentException("Invalid time preference entry: " + line);
				}

				GroupType group = GroupType.valueOf(tokens[0].trim().toUpperCase(Locale.ROOT));
				TimeBand timeBand = TimeBand.valueOf(tokens[1].trim().toUpperCase(Locale.ROOT));
				double value = Double.parseDouble(tokens[2].trim());
				values.get(group).put(timeBand, value);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to read baseline time preferences from " + file, e);
		}

		return values;
	}

	private static Path writeScaledTimePreferenceFile(Map<GroupType, EnumMap<TimeBand, Double>> baseline, double gamma,
			String originalPath, java.net.URL context) {
		try {
			String suffix = originalPath != null && !originalPath.isBlank()
					? originalPath.replaceAll("[^a-zA-Z0-9]+", "_")
					: "default";
			Path tempFile = Files.createTempFile("timePref_gamma_" + String.format(Locale.ROOT, "%.2f", gamma) + "_"
					+ suffix, ".csv");
			tempFile.toFile().deleteOnExit();

			try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
				writer.write("group,timeBand,value");
				writer.newLine();
				for (GroupType group : GroupType.values()) {
					for (TimeBand band : TimeBand.values()) {
						double base = baseline.get(group).get(band);
						double scaled = gamma * base;
						writer.write(group.name());
						writer.write(',');
						writer.write(band.name());
						writer.write(',');
						writer.write(Double.toString(scaled));
						writer.newLine();
					}
				}
			}

			return tempFile;
		} catch (IOException e) {
			throw new RuntimeException("Failed to write scaled time preference file for gamma=" + gamma, e);
		}
	}

	private static String resolveOutputDirectory(Config config, String explicitBase, String scenarioId) {
		if (explicitBase != null && !explicitBase.isBlank()) {
			return Path.of(explicitBase, scenarioId).toString();
		}
		String original = config.controller().getOutputDirectory();
		if (original == null || original.isBlank()) {
			return Path.of("output").resolve(scenarioId).toString();
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


