package org.matsim.a_shuai.EVRun_shuai;


import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SamplePopulation {
    public static void main(String[] args) {
        // 设置输入和输出文件路径
        String inputPopulationFile = "G:\\BaiduSyncdisk\\A_Paper1\\Simulation\\ev_population.xml";
        String outputPopulationFile = "G:\\BaiduSyncdisk\\A_Paper1\\Simulation\\sampled_population.xml";
        
        // 设置抽样数量，例如 1000 或 10000
        int sampleSize = 10000; // 或 sampleSize = 10000;

        // 创建 MATSim 配置和场景，读取人口文件
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        new PopulationReader(scenario).readFile(inputPopulationFile);
        Population population = scenario.getPopulation();

        // 将所有智能体放入列表并打乱顺序，实现随机抽样
        List<Person> allPersons = new ArrayList<>(population.getPersons().values());
        Collections.shuffle(allPersons);

        // 计算实际抽样数量（不能超过原始智能体总数）
        int finalSampleSize = Math.min(sampleSize, allPersons.size());

        // 记录选中的智能体 ID
        Set<Person> selectedPersons = new HashSet<>();
        for (int i = 0; i < finalSampleSize; i++) {
            selectedPersons.add(allPersons.get(i));
        }

        // 从人口中移除未被选中的智能体
        population.getPersons().entrySet().removeIf(entry -> !selectedPersons.contains(entry.getValue()));

        // 写出抽样后的人口文件
        new PopulationWriter(population, scenario.getNetwork()).write(outputPopulationFile);
    }
}
