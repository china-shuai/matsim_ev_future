package org.matsim.a_shuai.EVRun_shuai;


import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.PersonVehicles;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.VehicleWriterV1;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class EvpopulationVehicleWriting {
    public static void main(String[] args) {
        // 输入、输出文件路径
        String inputPopulationFile = "G:\\Eclipse work_EV\\EV_simulation\\sampled_population.xml";
        String inputVehiclesFile = "G:\\Eclipse work_EV\\EV_simulation\\evehicles.xml";
        String outputPopulationFile = "G:\\Eclipse work_EV\\EV_simulation\\ev_population.xml";
        String outputVehiclesFile = "G:\\Eclipse work_EV\\EV_simulation\\ev_vehicles.xml";

        // 创建 Config 和 Scenario，并读取 population 文件
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        new PopulationReader(scenario).readFile(inputPopulationFile);
        new MatsimVehicleReader(scenario.getVehicles()).readFile(inputVehiclesFile); 

        Population population = scenario.getPopulation();

        // 定义 EV 车辆类型（根据实际情况配置详细参数）
        VehicleType evType = scenario.getVehicles().getVehicleTypes().get(Id.create("EV_65.0kWh", VehicleType.class));
        VehicleType carType = scenario.getVehicles().getVehicleTypes().get(Id.create("car", VehicleType.class));

        // 定义生成 EV 出行的比例，例如 10% 的智能体生成 EV 出行计划
        double evVehicleAssignmentPercent = 1;
        Random random = new Random();
        
        //remove "bike" "pt"
        List<Id<Person>> personsToRemove = new ArrayList<>();
        for (Person person : population.getPersons().values()) {
            boolean isBikeTraveler = false;
            for (Plan plan : person.getPlans()) {
                for (Object planElement : plan.getPlanElements()) {
                    if (planElement instanceof Leg) {
                        Leg leg = (Leg) planElement;
                        if (! "car".equalsIgnoreCase(leg.getMode())) {
                            isBikeTraveler = true;
                            break;
                        }
                    }
                }
                if (isBikeTraveler) break;
            }
            if (isBikeTraveler) {
                personsToRemove.add(person.getId());
            }
        }
        // 将标记的智能体从 population 中移除
        for (Id<Person> personId : personsToRemove) {
            population.getPersons().remove(personId);
        }

        // 遍历所有智能体，针对存在 car 出行的智能体，按比例分配 EV 车辆
        for (Person person : population.getPersons().values()) {
            boolean hasCarLeg = false;
            // 判断该智能体是否有 car 出行
            for (Plan plan : person.getPlans()) {
                for (PlanElement pe : plan.getPlanElements()) {
                    if (pe instanceof Leg && "car".equalsIgnoreCase(((Leg) pe).getMode())) {
                        hasCarLeg = true;
                        break;
                    }
                }
                if (hasCarLeg) break;
            }
            // 如果存在 car 出行且随机抽样落在 10% 内，则为该智能体分配 EV 车辆
            if (hasCarLeg && random.nextDouble() <= evVehicleAssignmentPercent) {
                // 使用  personID 作为该智能体的 EV 车辆 ID，并设置初始SOC
                Id<Vehicle> evVehicleId = Id.create(person.getId(), Vehicle.class);
//                if (!scenario.getVehicles().getVehicles().containsKey(evVehicleId)) {
                    Vehicle evVehicle = VehicleUtils.getFactory().createVehicle(evVehicleId, evType);
                    evVehicle.getAttributes().putAttribute("initialSoc", 0.3);
                    scenario.getVehicles().addVehicle(evVehicle);
//                }
                // 在该智能体所有 car 出行 leg 中添加车辆 ID 属性
                for (Plan plan : person.getPlans()) {
                    for (PlanElement pe : plan.getPlanElements()) {
                        if (pe instanceof Leg) {
                            Leg leg = (Leg) pe;
                            if ("car".equalsIgnoreCase(leg.getMode())) {
                            	PersonVehicles pv = new PersonVehicles();
                            	pv.addModeVehicle("car", evVehicleId);
                            	person.getAttributes().putAttribute("vehicles", pv);
                            	person.getAttributes().putAttribute("wevc:active", Boolean.TRUE);
                            }
                        }
                    }
                }
            }
        }

        // 将修改后的 population 和车辆信息写出到新的文件中
        new PopulationWriter(population, scenario.getNetwork()).write(outputPopulationFile);
        new MatsimVehicleWriter(scenario.getVehicles()).writeFile(outputVehiclesFile);
    }
}

