package org.matsim.a_shuai;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 汇总 population 中不同 dwelling type（基于 person attribute {@code ev:dwellingType}）的 agent 数量、
 * home 活动数量与非 home 活动数量。
 *
 * <p>Usage:
 * <pre>
 *   java org.matsim.a_shuai.SummarizeDwellingActivities population.xml
 * </pre>
 *
 * <p>如果未提供参数，将使用脚本内置的默认路径。</p>
 */
public class SummarizeDwellingActivities {

    private static final boolean USE_HARDCODED_PATHS = true;
    private static final String DEFAULT_POPULATION_FILE = "/Users/S4065267/Matsim_ev/Simulation/100/population0.7HC.xml";

    private static final String DWELLING_ATTRIBUTE = "ev:dwellingType";

    public static void main(String[] args) {
        final Path populationPath;
        if (args.length >= 1) {
            populationPath = Paths.get(args[0]);
        } else if (USE_HARDCODED_PATHS) {
            populationPath = Paths.get(DEFAULT_POPULATION_FILE);
            System.out.println("[INFO] 未提供参数，使用默认 population: " + populationPath);
        } else {
            System.err.println("Usage: java org.matsim.a_shuai.SummarizeDwellingActivities <population.xml>");
            return;
        }

        if (!Files.exists(populationPath)) {
            System.err.println("[ERROR] population 文件不存在: " + populationPath);
            return;
        }

        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        new PopulationReader(scenario).readFile(populationPath.toString());

        Map<String, DwellingStats> statsByType = new HashMap<>();
        DwellingStats totalStats = new DwellingStats();

        for (Person person : scenario.getPopulation().getPersons().values()) {
            String type = getDwellingType(person);
            DwellingStats stats = statsByType.computeIfAbsent(type, t -> new DwellingStats());

            Plan plan = person.getSelectedPlan();
            if (plan == null && !person.getPlans().isEmpty()) {
                plan = person.getPlans().get(0);
            }

            int homeActs = 0;
            int nonHomeActs = 0;

            if (plan != null) {
                for (PlanElement planElement : plan.getPlanElements()) {
                    if (planElement instanceof Activity activity) {
                        if (isHomeActivity(activity)) {
                            homeActs++;
                        } else {
                            nonHomeActs++;
                        }
                    }
                }
            }

            stats.agentCount++;
            stats.homeActivities += homeActs;
            stats.nonHomeActivities += nonHomeActs;

            totalStats.agentCount++;
            totalStats.homeActivities += homeActs;
            totalStats.nonHomeActivities += nonHomeActs;
        }

        System.out.println("dwellingType,agents,homeActivities,nonHomeActivities");
        statsByType.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    DwellingStats s = entry.getValue();
                    System.out.printf(Locale.US, "%s,%d,%d,%d%n",
                            entry.getKey(),
                            s.agentCount,
                            s.homeActivities,
                            s.nonHomeActivities);
                });

        System.out.printf(Locale.US, "TOTAL,%d,%d,%d%n",
                totalStats.agentCount,
                totalStats.homeActivities,
                totalStats.nonHomeActivities);
    }

    private static String getDwellingType(Person person) {
        Object attr = person.getAttributes().getAttribute(DWELLING_ATTRIBUTE);
        if (attr == null) {
            return "UNKNOWN";
        }
        String value = attr.toString().trim();
        return value.isEmpty() ? "UNKNOWN" : value;
    }

    private static boolean isHomeActivity(Activity activity) {
        String type = activity.getType();
        if (type == null) {
            return false;
        }
        String normalized = type.toLowerCase(Locale.ROOT);
        return normalized.contains("home");
    }

    private static class DwellingStats {
        int agentCount = 0;
        long homeActivities = 0;
        long nonHomeActivities = 0;
    }
}

