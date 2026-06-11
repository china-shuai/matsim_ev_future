package org.matsim.a_shuai;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 移除 population 中所有 agent 的 {@code ev:initialSoc} 与 {@code sevc:criticalSoc} person attributes。
 *
 * <p>Usage:
 * <pre>
 *   java org.matsim.a_shuai.RemoveEvSocAttributes inputPopulation.xml outputPopulation.xml
 * </pre>
 * 如果省略参数，将使用脚本内置的默认路径并覆盖输出。
 */
public class RemoveEvSocAttributes {

    private static final boolean USE_HARDCODED_PATHS = true;
    private static final String DEFAULT_POPULATION_IN = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/population_ev_with_attrs.xml";
    private static final String DEFAULT_POPULATION_OUT = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/population_ev_no_soc_attrs.xml";

    private static final String INITIAL_SOC_ATTRIBUTE = "ev:initialSoc";
    private static final String CRITICAL_SOC_ATTRIBUTE = "sevc:criticalSoc";

    public static void main(String[] args) throws IOException {
        final Path populationIn;
        final Path populationOut;

        if (args.length >= 2) {
            populationIn = Paths.get(args[0]);
            populationOut = Paths.get(args[1]);
        } else if (USE_HARDCODED_PATHS) {
            populationIn = Paths.get(DEFAULT_POPULATION_IN);
            populationOut = Paths.get(DEFAULT_POPULATION_OUT);
            System.out.println("[INFO] 未提供参数，使用默认路径。");
        } else {
            System.err.println("Usage: java org.matsim.a_shuai.RemoveEvSocAttributes <population_in.xml> <population_out.xml>");
            return;
        }

        if (!Files.exists(populationIn)) {
            System.err.println("[ERROR] population 输入文件不存在: " + populationIn);
            return;
        }
        if (populationOut.getParent() != null) {
            Files.createDirectories(populationOut.getParent());
        }

        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        new PopulationReader(scenario).readFile(populationIn.toString());

        int removedInitialSoc = 0;
        int removedCriticalSoc = 0;

        for (Person person : scenario.getPopulation().getPersons().values()) {
            if (person.getAttributes().getAttribute(INITIAL_SOC_ATTRIBUTE) != null) {
                person.getAttributes().removeAttribute(INITIAL_SOC_ATTRIBUTE);
                removedInitialSoc++;
            }
            if (person.getAttributes().getAttribute(CRITICAL_SOC_ATTRIBUTE) != null) {
                person.getAttributes().removeAttribute(CRITICAL_SOC_ATTRIBUTE);
                removedCriticalSoc++;
            }
        }

        new PopulationWriter(scenario.getPopulation()).write(populationOut.toString());
        System.out.printf("[DONE] 已删除 %d 个 agent 的 %s，%d 个 agent 的 %s，输出文件：%s%n",
                removedInitialSoc, INITIAL_SOC_ATTRIBUTE,
                removedCriticalSoc, CRITICAL_SOC_ATTRIBUTE,
                populationOut);
    }
}

