package org.matsim.a_shuai;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;

import java.util.Set;
import java.util.stream.Collectors;

public class FilterEvPopulation {

    private static final boolean USE_HARDCODED_PATHS = true;
    private static final String DEFAULT_POPULATION_FILE = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/100/ev_population.xml";
    private static final String DEFAULT_VEHICLE_FILE = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/100/ev_vehicles.xml";
    private static final String DEFAULT_OUTPUT_FILE = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/100/population_ev.xml";

    /**
     * 用法：
     *   java -cp your.jar tools.FilterEvPopulation population.xml vehicles.xml population_ev.xml
     */
    public static void main(String[] args) {
        final String popIn;
        final String vehIn;
        final String popOut;
        if (args.length >= 3) {
            popIn = args[0];
            vehIn = args[1];
            popOut = args[2];
        } else if (USE_HARDCODED_PATHS) {
            if (DEFAULT_POPULATION_FILE.isBlank() || DEFAULT_VEHICLE_FILE.isBlank() || DEFAULT_OUTPUT_FILE.isBlank()) {
                throw new IllegalStateException("默认路径未配置，请设置 DEFAULT_* 常量或通过命令行参数传入路径。");
            }
            popIn = DEFAULT_POPULATION_FILE;
            vehIn = DEFAULT_VEHICLE_FILE;
            popOut = DEFAULT_OUTPUT_FILE;
            System.out.println("[INFO] 未提供参数，使用脚本内置路径。");
        } else {
            System.err.println("Usage: java tools.FilterEvPopulation <population.xml> <vehicles.xml> <population_ev.xml>");
            System.exit(1);
            return;
        }

        // 1) 读取 vehicles.xml（你已确认全部为 EV）
        Vehicles vehicles = VehicleUtils.createVehiclesContainer();
        new MatsimVehicleReader(vehicles).readFile(vehIn);

        // 将 Vehicle 的 Id 转成 Person 的 Id（字符串一致即可）
        Set<Id<Person>> evPersonIds = vehicles.getVehicles()
                .keySet()
                .stream()
                .map(vId -> Id.createPersonId(vId.toString()))
                .collect(Collectors.toSet());

        System.out.println("[INFO] EV vehicle count = " + evPersonIds.size());

        // 2) 读取 population.xml
        Config cfg = ConfigUtils.createConfig();
        Scenario sc = ScenarioUtils.createScenario(cfg);
        new PopulationReader(sc).readFile(popIn);
        Population pop = sc.getPopulation();

        System.out.println("[INFO] Population persons = " + pop.getPersons().size());

        // 3) 构建只含 EV 出行者的新 Population
        Config cfgOut = ConfigUtils.createConfig();
        Scenario scOut = ScenarioUtils.createScenario(cfgOut);
        Population popOutContainer = scOut.getPopulation();

        int kept = 0;
        for (Person person : pop.getPersons().values()) {
            if (evPersonIds.contains(person.getId())) {
                // 复制 person（含属性与全部 plans）
                Person p2 = popOutContainer.getFactory().createPerson(person.getId());
                AttributesUtils.copyAttributesFromTo(person, p2);
                for (Plan plan : person.getPlans()) {
                    Plan planCopy = popOutContainer.getFactory().createPlan();
                    planCopy.setPerson(p2);
                    PopulationUtils.copyFromTo(plan, planCopy);
                    p2.addPlan(planCopy);
                    if (plan == person.getSelectedPlan()) {
                        p2.setSelectedPlan(planCopy);
                    }
                }
                popOutContainer.addPerson(p2);
                kept++;
            }
        }

        System.out.println(String.format("[INFO] EV persons matched = %d", kept));

        // 4) 写出新 population
        new PopulationWriter(popOutContainer).write(popOut);
        System.out.println("[DONE] Written: " + popOut);
    }
}

