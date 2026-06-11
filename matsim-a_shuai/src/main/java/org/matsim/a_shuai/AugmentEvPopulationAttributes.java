package org.matsim.a_shuai;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 基于 EV population、EV household CSV 与 person-household 映射文件，
 * 为每个 agent 添加入户型与家用充电桩属性。
 */
public class AugmentEvPopulationAttributes {

    private static final boolean USE_HARDCODED_PATHS = true;
    private static final String DEFAULT_POPULATION_IN = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/population_ev.xml";
    private static final String DEFAULT_POPULATION_OUT = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/population_ev_with_attrs.xml";
    private static final String DEFAULT_MAPPING_FILE = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/ev_person_household_map.csv";
    private static final String DEFAULT_HOUSEHOLD_FILE = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/ev_households.csv";

    private static final String ATTR_DWELLING = "ev:dwellingType";
    private static final String ATTR_HOME_CHARGER = "ev:hasHomeCharger";

    private static final double APARTMENT_HAS_HOME_CHARGER_RATE = initApartmentHasChargerRate(0.92);
    private static final long HOME_CHARGER_RANDOM_SEED = initHomeChargerSeed(2025L);

    private static final String GROUP_APARTMENT = "APARTMENT";
    private static final String GROUP_HOUSE_WITH_PV = "HOUSE_WITH_PV";
    private static final String GROUP_HOUSE_NO_PV = "HOUSE_NO_PV";

    public static void main(String[] args) throws IOException {
        final Path populationIn;
        final Path householdCsv;
        final Path mappingCsv;
        final Path populationOut;

        if (args.length >= 4) {
            populationIn = Paths.get(args[0]);
            householdCsv = Paths.get(args[1]);
            mappingCsv = Paths.get(args[2]);
            populationOut = Paths.get(args[3]);
        } else if (USE_HARDCODED_PATHS) {
            populationIn = Paths.get(DEFAULT_POPULATION_IN);
            householdCsv = Paths.get(DEFAULT_HOUSEHOLD_FILE);
            mappingCsv = Paths.get(DEFAULT_MAPPING_FILE);
            populationOut = Paths.get(DEFAULT_POPULATION_OUT);
            System.out.println("[INFO] 未提供命令行参数，使用脚本内置路径运行。");
        } else {
            System.err.println("Usage: java AugmentEvPopulationAttributes <population_ev_in.xml> <ev_households.csv> <person_household_map.csv> <population_ev_out.xml>");
            System.exit(1);
            return;
        }

        ensureFileExists(populationIn, "EV population");
        ensureFileExists(householdCsv, "households csv");
        ensureFileExists(mappingCsv, "person-household mapping csv");

        Map<String, HouseholdInfo> households = loadHouseholds(householdCsv);
        Map<String, String> personToHousehold = loadPersonHouseholdMapping(mappingCsv);

        augmentPopulation(populationIn, populationOut, households, personToHousehold);
    }

    private static void ensureFileExists(Path path, String label) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("找不到 " + label + " 文件: " + path);
        }
    }

    private static Map<String, HouseholdInfo> loadHouseholds(Path csvPath) throws IOException {
        List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("household CSV 为空: " + csvPath);
        }
        List<String> header = parseCsvLine(lines.get(0));
        int idIdx = findColumn(header, "householdid", "household_id", "hhid", "id");
        int dwellingIdx = findColumn(header, "dwelling_type", "dwellingtype", "type");
        int pvIdx = findColumn(header, "haspv", "has_pv", "pv", "hassolar");

        if (idIdx < 0 || dwellingIdx < 0) {
            throw new IllegalArgumentException("CSV 表头需包含 householdId 与 dwelling_type 列。实际表头: " + header);
        }
        if (pvIdx < 0) {
            System.out.println("[WARN] 未找到 hasPV 列，默认按 false 处理。");
        }

        Map<String, HouseholdInfo> result = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> cells = parseCsvLine(line);
            if (cells.size() <= Math.max(idIdx, Math.max(dwellingIdx, pvIdx < 0 ? 0 : pvIdx))) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 行列数不足: " + line);
            }
            String householdId = cells.get(idIdx).trim();
            String dwelling = cells.get(dwellingIdx).trim();
            boolean hasPv = pvIdx >= 0 && parseBoolean(cells.get(pvIdx));

            if (result.putIfAbsent(householdId, new HouseholdInfo(householdId, dwelling, hasPv)) != null) {
                System.out.println("[WARN] duplicate householdId '" + householdId + "'，沿用首个记录。");
            }
        }
        return result;
    }

    private static Map<String, String> loadPersonHouseholdMapping(Path csvPath) throws IOException {
        List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("person-household CSV 为空: " + csvPath);
        }
        List<String> header = parseCsvLine(lines.get(0));
        int personIdx = findColumn(header, "personid", "person_id", "agentid", "id");
        int householdIdx = findColumn(header, "householdid", "household_id", "hhid");
        if (personIdx < 0 || householdIdx < 0) {
            throw new IllegalArgumentException("映射 CSV 表头需包含 personId 与 householdId 列。实际表头: " + header);
        }

        Map<String, String> map = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> cells = parseCsvLine(line);
            if (cells.size() <= Math.max(personIdx, householdIdx)) {
                throw new IllegalArgumentException("映射 CSV 第 " + (i + 1) + " 行列数不足: " + line);
            }
            String personId = cells.get(personIdx).trim();
            String householdId = cells.get(householdIdx).trim();
            if (!personId.isEmpty() && !householdId.isEmpty()) {
                map.put(personId, householdId);
            }
        }
        return map;
    }

    private static void augmentPopulation(Path populationIn,
                                          Path populationOut,
                                          Map<String, HouseholdInfo> households,
                                          Map<String, String> personToHousehold) throws IOException {
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        new PopulationReader(scenario).readFile(populationIn.toString());

        Random random = new Random(HOME_CHARGER_RANDOM_SEED);

        int updated = 0;
        int missingMapping = 0;
        int missingHousehold = 0;
        int apartmentCount = 0;
        int apartmentNoCharger = 0;

        for (Person person : scenario.getPopulation().getPersons().values()) {
            String personId = person.getId().toString();
            String householdId = personToHousehold.get(personId);
            if (householdId == null) {
                missingMapping++;
                continue;
            }
            HouseholdInfo info = households.get(householdId);
            if (info == null) {
                missingHousehold++;
                continue;
            }
            String groupType = determineGroupType(info);
            boolean hasHomeCharger = determineHomeCharger(groupType, random);

            person.getAttributes().putAttribute(ATTR_DWELLING, groupType);
            person.getAttributes().putAttribute(ATTR_HOME_CHARGER, hasHomeCharger);

            if (GROUP_APARTMENT.equals(groupType)) {
                apartmentCount++;
                if (!hasHomeCharger) {
                    apartmentNoCharger++;
                }
            }

            updated++;
        }

        if (populationOut.getParent() != null) {
            Files.createDirectories(populationOut.getParent());
        }
        new PopulationWriter(scenario.getPopulation()).write(populationOut.toString());

        System.out.println("[INFO] 已更新 agent 数量: " + updated);
        if (missingMapping > 0) {
            System.out.println("[WARN] 缺少 household 映射的 agent 数量: " + missingMapping);
        }
        if (missingHousehold > 0) {
            System.out.println("[WARN] 在 household 文件中未找到记录的 agent 数量: " + missingHousehold);
        }
        if (apartmentCount > 0) {
            double actualRate = apartmentNoCharger / (double) apartmentCount;
            System.out.println("[INFO] Apartment 住户数量: " + apartmentCount + "；无家充比例: "
                    + String.format(Locale.US, "%.2f%%", actualRate * 100));
        }
    }

    private static String determineGroupType(HouseholdInfo info) {
        String normalized = info.dwellingType.toLowerCase(Locale.ROOT);
        if (normalized.contains("apartment")) {
            return GROUP_APARTMENT;
        } else if (normalized.contains("house") || normalized.contains("townhouse")) {
            return info.hasPv ? GROUP_HOUSE_WITH_PV : GROUP_HOUSE_NO_PV;
        } else {
            System.out.println("[WARN] 未识别的 dwelling_type '" + info.dwellingType
                    + "' (householdId=" + info.householdId + ")，默认视为无 PV 独栋。");
            return info.hasPv ? GROUP_HOUSE_WITH_PV : GROUP_HOUSE_NO_PV;
        }
    }

    private static boolean determineHomeCharger(String groupType, Random random) {
        if (GROUP_APARTMENT.equals(groupType)) {
            return random.nextDouble() < APARTMENT_HAS_HOME_CHARGER_RATE;
        }
        return true;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    private static int findColumn(List<String> header, String... candidates) {
        for (int i = 0; i < header.size(); i++) {
            String normalized = header.get(i).trim().toLowerCase(Locale.ROOT);
            for (String candidate : candidates) {
                if (normalized.equals(candidate)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean parseBoolean(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("1")
                || normalized.equals("true")
                || normalized.equals("yes")
                || normalized.equals("y")
                || normalized.equals("t");
    }

    private static double initApartmentHasChargerRate(double defaultValue) {
        String value = System.getProperty("ev.population.apartmentHasChargerRate");
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (parsed < 0.0 || parsed > 1.0) {
                throw new IllegalArgumentException("Apartment 有家充比例需在 0 与 1 之间: " + parsed);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析 apartment 有家充比例参数 ev.population.apartmentHasChargerRate=" + value, e);
        }
    }

    private static long initHomeChargerSeed(long defaultSeed) {
        String value = System.getProperty("ev.population.homeChargerSeed");
        if (value == null || value.isBlank()) {
            return defaultSeed;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析家充随机种子参数 ev.population.homeChargerSeed=" + value, e);
        }
    }

    private static class HouseholdInfo {
        final String householdId;
        final String dwellingType;
        final boolean hasPv;

        HouseholdInfo(String householdId, String dwellingType, boolean hasPv) {
            this.householdId = householdId;
            this.dwellingType = dwellingType;
            this.hasPv = hasPv;
        }
    }
}

