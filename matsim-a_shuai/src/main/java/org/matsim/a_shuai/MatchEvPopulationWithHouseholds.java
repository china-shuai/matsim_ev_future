package org.matsim.a_shuai;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 读取 EV population 与 household CSV，通过家庭坐标对齐 agent 与 householdId。
 * 支持命令行参数或脚本内置路径。
 */
public class MatchEvPopulationWithHouseholds {

    private static final boolean USE_HARDCODED_PATHS = true;
    private static final String DEFAULT_POPULATION_FILE = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/100/population_ev.xml";
    private static final String DEFAULT_HOUSEHOLDS_CSV = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/Data/HouseholdV3.csv";
    private static final String DEFAULT_MAPPING_OUTPUT = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/100/ev_person_household_map.csv";
    private static final String DEFAULT_POPULATION_OUTPUT = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/100/ev_population_with_household.xml";

    /**
     * 坐标量化精度：
     *   - >0 时按指定精度（米^-1）四舍五入后再匹配，例如 100 表示 0.01 m 精度；
     *   - <=0 时不做量化，直接使用原始坐标进行精确比较。
     * 可通过 JVM 参数 -Dev.match.coordScale=xxx 覆盖默认值。
     */
    private static final double COORD_SCALE = initCoordScale(0); // 默认 0.001 m 精度
    private static final String PERSON_ATTRIBUTE_HOUSEHOLD = "householdId";

    public static void main(String[] args) throws IOException {
        final Path populationFile;
        final Path householdCsvFile;
        final Path mappingOutputFile;
        final Path populationOutputFile;

        if (args.length >= 3) {
            populationFile = Paths.get(args[0]);
            householdCsvFile = Paths.get(args[1]);
            mappingOutputFile = Paths.get(args[2]);
            if (args.length >= 4) {
                populationOutputFile = Paths.get(args[3]);
            } else {
                populationOutputFile = null;
            }
        } else if (USE_HARDCODED_PATHS) {
            populationFile = Paths.get(DEFAULT_POPULATION_FILE);
            householdCsvFile = Paths.get(DEFAULT_HOUSEHOLDS_CSV);
            mappingOutputFile = Paths.get(DEFAULT_MAPPING_OUTPUT);
            populationOutputFile = DEFAULT_POPULATION_OUTPUT == null || DEFAULT_POPULATION_OUTPUT.isBlank()
                    ? null
                    : Paths.get(DEFAULT_POPULATION_OUTPUT);
            System.out.println("[INFO] 未提供命令行参数，使用脚本内置路径运行。");
        } else {
            System.err.println("Usage: java MatchEvPopulationWithHouseholds <population.xml> <households.csv> <mapping.csv> [population_with_household.xml]");
            System.exit(1);
            return;
        }

        ensureFileExists(populationFile, "population");
        ensureFileExists(householdCsvFile, "households csv");

        Map<QuantizedCoord, HouseholdEntry> householdIndex = loadHouseholds(householdCsvFile);
        System.out.println("[INFO] Household CSV 记录数 = " + householdIndex.size());

        MatchingResult result = matchPopulation(populationFile, householdIndex);

        writeMapping(mappingOutputFile, result.personToHousehold);
        System.out.println("[INFO] 已写出 mapping 文件: " + mappingOutputFile);

        if (populationOutputFile != null) {
            writeUpdatedPopulation(populationFile, populationOutputFile, result.personToHousehold);
            System.out.println("[INFO] 已写出带 householdId 属性的 population 文件: " + populationOutputFile);
        }

        if (!result.unmatchedPersons.isEmpty()) {
            System.out.println("[WARN] 未匹配到 household 的 agent 数量: " + result.unmatchedPersons.size());
            System.out.println("       agentId 列表: " + result.unmatchedPersons);
        }

        if (!result.unusedHouseholds.isEmpty()) {
            System.out.println("[WARN] 有 " + result.unusedHouseholds.size() + " 个 household 坐标未被任何 agent 使用。");
            System.out.println("       示例: " + result.unusedHouseholds.values().stream()
                    .limit(5)
                    .map(entry -> entry.householdId + "@" + formatCoord(entry.x, entry.y))
                    .collect(Collectors.toList()));
        }
    }

    private static void ensureFileExists(Path path, String label) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("找不到 " + label + " 文件: " + path);
        }
    }

    private static Map<QuantizedCoord, HouseholdEntry> loadHouseholds(Path csvPath) throws IOException {
        List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("household CSV 为空: " + csvPath);
        }
        List<String> header = parseCsvLine(lines.get(0));
        int idIdx = findColumn(header, "householdid", "household_id", "hhid", "id");
        int xIdx = findColumn(header, "home_x", "homex", "x");
        int yIdx = findColumn(header, "home_y", "homey", "y");
        if (idIdx < 0 || xIdx < 0 || yIdx < 0) {
            throw new IllegalArgumentException("CSV 表头需包含 householdId, home_x, home_y 列。实际表头: " + header);
        }

        Map<QuantizedCoord, HouseholdEntry> index = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> cells = parseCsvLine(line);
            if (cells.size() <= Math.max(idIdx, Math.max(xIdx, yIdx))) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 行列数不足: " + line);
            }
            String hhId = cells.get(idIdx).trim();
            double homeX = Double.parseDouble(cells.get(xIdx).trim());
            double homeY = Double.parseDouble(cells.get(yIdx).trim());
            QuantizedCoord key = QuantizedCoord.of(homeX, homeY);
            HouseholdEntry entry = new HouseholdEntry(hhId, homeX, homeY);

            HouseholdEntry previous = index.putIfAbsent(key, entry);
            if (previous != null) {
                System.out.println("[WARN] 坐标重复，沿用首个 householdId: " + formatCoord(homeX, homeY)
                        + " -> [" + previous.householdId + ", " + hhId + "]");
            }
        }
        return index;
    }

    private static double initCoordScale(double defaultValue) {
        String value = System.getProperty("ev.match.coordScale");
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析坐标精度参数 ev.match.coordScale=" + value, e);
        }
    }

    private static MatchingResult matchPopulation(Path populationXml, Map<QuantizedCoord, HouseholdEntry> householdIndex) {
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        new PopulationReader(scenario).readFile(populationXml.toString());

        Map<String, String> personToHousehold = new java.util.LinkedHashMap<>();
        Set<QuantizedCoord> remaining = new java.util.HashSet<>(householdIndex.keySet());
        List<String> unmatched = new ArrayList<>();

        for (Person person : scenario.getPopulation().getPersons().values()) {
            Plan plan = person.getSelectedPlan();
            if (plan == null && !person.getPlans().isEmpty()) {
                plan = person.getPlans().get(0);
            }
            Activity home = plan == null ? null : findHomeActivity(plan);
            Coord coord = home == null ? null : home.getCoord();
            if (coord == null) {
                unmatched.add(person.getId().toString());
                continue;
            }

            QuantizedCoord key = QuantizedCoord.of(coord.getX(), coord.getY());
            HouseholdEntry entry = householdIndex.get(key);
            if (entry == null) {
                unmatched.add(person.getId().toString());
                continue;
            }
            personToHousehold.put(person.getId().toString(), entry.householdId);
            remaining.remove(key);
        }
        Map<QuantizedCoord, HouseholdEntry> unused = new HashMap<>();
        for (QuantizedCoord key : remaining) {
            HouseholdEntry entry = householdIndex.get(key);
            if (entry != null) {
                unused.put(key, entry);
            }
        }
        return new MatchingResult(personToHousehold, unmatched, unused);
    }

    private static void writeMapping(Path output, Map<String, String> personToHousehold) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("personId,householdId");
            writer.newLine();
            for (Map.Entry<String, String> entry : personToHousehold.entrySet()) {
                String personId = entry.getKey();
                String householdId = entry.getValue();
                writer.write(personId);
                writer.write(',');
                writer.write(householdId);
                writer.newLine();
            }
        }
    }

    private static void writeUpdatedPopulation(Path populationXml, Path outputXml, Map<String, String> personToHousehold) throws IOException {
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        new PopulationReader(scenario).readFile(populationXml.toString());

        for (Person person : scenario.getPopulation().getPersons().values()) {
            String hhId = personToHousehold.get(person.getId().toString());
            if (hhId != null) {
                person.getAttributes().putAttribute(PERSON_ATTRIBUTE_HOUSEHOLD, hhId);
            }
        }
        if (outputXml.getParent() != null) {
            Files.createDirectories(outputXml.getParent());
        }
        new PopulationWriter(scenario.getPopulation()).write(outputXml.toString());
    }

    private static Activity findHomeActivity(Plan plan) {
        Activity firstActivity = null;
        for (PlanElement planElement : plan.getPlanElements()) {
            if (planElement instanceof Activity activity) {
                if (firstActivity == null) {
                    firstActivity = activity;
                }
                String type = activity.getType();
                if (type != null && type.toLowerCase(Locale.ROOT).contains("home")) {
                    return activity;
                }
            }
        }
        return firstActivity;
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

    private static String formatCoord(double x, double y) {
        DecimalFormat df = new DecimalFormat("0.###", DecimalFormatSymbols.getInstance(Locale.US));
        return "(" + df.format(x) + ", " + df.format(y) + ")";
    }

    private static class HouseholdEntry {
        final String householdId;
        final double x;
        final double y;

        HouseholdEntry(String householdId, double x, double y) {
            this.householdId = householdId;
            this.x = x;
            this.y = y;
        }
    }

    private static class QuantizedCoord {
        final long xKey;
        final long yKey;

        private QuantizedCoord(long xKey, long yKey) {
            this.xKey = xKey;
            this.yKey = yKey;
        }

        static QuantizedCoord of(double x, double y) {
            if (COORD_SCALE > 0) {
                long xi = Math.round(x * COORD_SCALE);
                long yi = Math.round(y * COORD_SCALE);
                return new QuantizedCoord(xi, yi);
            } else {
                long xi = Double.doubleToLongBits(x);
                long yi = Double.doubleToLongBits(y);
                return new QuantizedCoord(xi, yi);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof QuantizedCoord)) return false;
            QuantizedCoord that = (QuantizedCoord) o;
            return xKey == that.xKey && yKey == that.yKey;
        }

        @Override
        public int hashCode() {
            return Objects.hash(xKey, yKey);
        }
    }

    private static class MatchingResult {
        final Map<String, String> personToHousehold;
        final List<String> unmatchedPersons;
        final Map<QuantizedCoord, HouseholdEntry> unusedHouseholds;

        MatchingResult(Map<String, String> personToHousehold,
                       List<String> unmatchedPersons,
                       Map<QuantizedCoord, HouseholdEntry> unusedHouseholds) {
            this.personToHousehold = personToHousehold;
            this.unmatchedPersons = unmatchedPersons;
            this.unusedHouseholds = unusedHouseholds;
        }
    }
}

