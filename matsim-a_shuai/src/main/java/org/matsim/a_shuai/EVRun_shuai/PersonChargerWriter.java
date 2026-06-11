
package org.matsim.a_shuai.EVRun_shuai;


import com.opencsv.CSVReader;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.ev.infrastructure.Charger;
import org.matsim.contrib.ev.infrastructure.ChargerSpecification;
import org.matsim.contrib.ev.infrastructure.ChargerWriter;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructureSpecification;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructureUtils;
import org.matsim.contrib.ev.infrastructure.ImmutableChargerSpecification;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

		/**
		 * 为每个 EV 驾驶者生成私人充电站，
		 * 使用 mapping_ev_driver_with_link.csv 中的 person_id 和 linkId 列
		 * 属性 sevc:persons，并增加 PERSONS_CHARGER_ATTRIBUTE
		 */
		public class PersonChargerWriter {
    private static final String MAPPING_CSV_DEFAULT = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/Data/Population10/new model/mapping_ev_driver_with_link.csv";
    private static final String POPULATION_XML_DEFAULT = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/10/population_ev_no_soc_attrs.xml";
    private static final String OUTPUT_XML_DEFAULT  = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/private_chargersV2.xml";

    public static void main(String[] args) throws Exception {
        String mappingCsv = args.length >= 1 ? args[0] : MAPPING_CSV_DEFAULT;
        String populationXml = args.length >= 2 ? args[1] : POPULATION_XML_DEFAULT;
        String outputXml = args.length >= 3 ? args[2] : OUTPUT_XML_DEFAULT;

        System.out.println("[INFO] Charger mapping CSV: " + mappingCsv);
        System.out.println("[INFO] Population with attributes: " + populationXml);
        System.out.println("[INFO] Output charger file: " + outputXml);

        Map<String, String> personToLink = loadPersonLinks(mappingCsv);
        Set<String> personsWithHomeCharger = loadPersonsWithHomeCharger(populationXml);

        ChargingInfrastructureSpecification infra = ChargingInfrastructureUtils.createChargingInfrastructureSpecification();
        int idx = 0;
        Set<String> missingLink = new HashSet<>();

        for (String personId : personsWithHomeCharger) {
            String linkId = personToLink.get(personId);
            if (linkId == null || linkId.isBlank()) {
                missingLink.add(personId);
                continue;
            }

            AttributesImpl attrs = new AttributesImpl();
            attrs.putAttribute("sevc:persons", personId);

            ChargerSpecification spec = ImmutableChargerSpecification.newBuilder()
                    .id(Id.create("privateCharger_" + idx++, Charger.class))
                    .chargerType("type1")
                    .linkId(Id.createLinkId(linkId))
                    .plugCount(1)
                    .plugPower(10000)
                    .attributes(attrs)
                    .build();

            infra.addChargerSpecification(spec);
        }

        new ChargerWriter(infra.getChargerSpecifications().values().stream())
                .write(outputXml);

        System.out.println("[INFO] population 中标记有家充的人数: " + personsWithHomeCharger.size());
        System.out.println("[INFO] 实际生成的充电桩数量: " + infra.getChargerSpecifications().size());
        if (!missingLink.isEmpty()) {
            System.out.println("[WARN] population 中标记有家充但在 CSV 中未找到 linkId 的人数: " + missingLink.size());
            missingLink.stream().limit(10).forEach(id -> System.out.println("    " + id));
        }

        System.out.println("私有充电站已生成: " + outputXml);
    }

    private static Map<String, String> loadPersonLinks(String csvFile) throws Exception {
        Map<String, String> map = new HashMap<>();
        try (CSVReader reader = new CSVReader(new FileReader(csvFile))) {
            String[] header = reader.readNext();
            int idxPid = java.util.Arrays.asList(header).indexOf("person_id");
            int idxLink = java.util.Arrays.asList(header).indexOf("nearest_link");
            if (idxPid < 0 || idxLink < 0) {
                throw new RuntimeException("CSV 中未找到 person_id 或 nearest_link 列");
            }
            String[] line;
            while ((line = reader.readNext()) != null) {
                String personId = line[idxPid].trim();
                String linkId = line[idxLink].trim();
                if (!personId.isEmpty() && !linkId.isEmpty()) {
                    map.put(personId, linkId);
                }
            }
        }
        return map;
    }

    private static Set<String> loadPersonsWithHomeCharger(String populationFile) {
        Set<String> persons = new HashSet<>();
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        new PopulationReader(scenario).readFile(populationFile);
        scenario.getPopulation().getPersons().values().forEach(person -> {
            if (person == null) {
                return;
            }
            Object attr = person.getAttributes().getAttribute("ev:hasHomeCharger");
            if (attr instanceof Boolean && (Boolean) attr) {
                persons.add(person.getId().toString());
            }
        });
        return persons;
    }

		}



