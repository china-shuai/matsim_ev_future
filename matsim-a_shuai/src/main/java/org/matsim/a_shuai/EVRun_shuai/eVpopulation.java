package org.matsim.a_shuai.EVRun_shuai;

import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.ev.strategic.CriticalAlternativeProvider;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;

import com.opencsv.CSVReader;

public class eVpopulation {
    @SuppressWarnings("unused")
    
    //清理bike和pt，之后使用SamplePopulation找出样本。
	public static void main(String[] args) {
        // 定义输入和输出文件路径
        String inputPopulationFile = "G:\\Eclipse work_EV\\EV_simulation\\data\\plan.xml";
        String buildingtype = "G:\\Eclipse work_EV\\EV_simulation\\data\\modified_persons.csv";
        String outputPopulationFile = "G:\\Eclipse work_EV\\EV_simulation\\output_populationV2.0.xml";

        // 创建 Config 和 Scenario 对象，并读取 population 文件
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        new PopulationReader(scenario).readFile(inputPopulationFile);

        Population population = scenario.getPopulation();
        
        // 先移除包含 bike 出行方式的智能体
        List<Id<Person>> personsToRemove = new ArrayList<>();
        for (Person person : population.getPersons().values()) {
            boolean isBikeTraveler = false;
            for (Plan plan : person.getPlans()) {
                for (Object planElement : plan.getPlanElements()) {
                    if (planElement instanceof Leg) {
                        Leg leg = (Leg) planElement;
                        if ("bike".equalsIgnoreCase(leg.getMode()) || "pt".equalsIgnoreCase(leg.getMode())) {
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
        


        // 遍历所有智能体和其计划，将出行方式为 "car" 的 leg 修改为 "EV"
        for (Person person : population.getPersons().values()) {
        	person.getAttributes().putAttribute(CriticalAlternativeProvider.CRITICAL_SOC_PERSON_ATTRIBUTE, 0.2);
            
        }

        // 将修改后的 population 写出到新的文件中
        new PopulationWriter(population, scenario.getNetwork()).write(outputPopulationFile);
        System.out.println("done"); 
    }
}
