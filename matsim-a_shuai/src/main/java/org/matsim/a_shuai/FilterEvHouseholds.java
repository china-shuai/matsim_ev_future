package org.matsim.a_shuai;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * 读取完整 household CSV，过滤到仅包含 EV household，并按照户型为 house/townhouse 的居民
 * 以指定概率分配光伏（apartment 恒为 false），输出新增 hasPV 列的 CSV。
 */
public class FilterEvHouseholds {

    private static final boolean USE_HARDCODED_PATHS = true;
    // private static final String DEFAULT_MAPPING_FILE = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/100/ev_person_household_map.csv";
    // private static final String DEFAULT_HOUSEHOLD_FILE = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/Data/Population10/new model/HouseholdV8.csv";
    // private static final String DEFAULT_OUTPUT_FILE = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/100/ev_households.csv";

    private static final String DEFAULT_MAPPING_FILE = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/ev_person_household_map.csv";
    private static final String DEFAULT_HOUSEHOLD_FILE = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/HouseholdV8.csv";
    private static final String DEFAULT_OUTPUT_FILE = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/ev_households.csv";


    private static final String PV_COLUMN_NAME = "hasPV";
    private static final double PV_PROBABILITY = initPvProbability(0.8);
    private static final long PV_RANDOM_SEED = initPvSeed(2025L);

    public static void main(String[] args) throws IOException {
        final Path mappingFile;
        final Path householdFile;
        final Path outputFile;

        if (args.length >= 3) {
            mappingFile = Paths.get(args[0]);
            householdFile = Paths.get(args[1]);
            outputFile = Paths.get(args[2]);
        } else if (USE_HARDCODED_PATHS) {
            mappingFile = Paths.get(DEFAULT_MAPPING_FILE);
            householdFile = Paths.get(DEFAULT_HOUSEHOLD_FILE);
            outputFile = Paths.get(DEFAULT_OUTPUT_FILE);
            System.out.println("[INFO] 未提供命令行参数，使用脚本内置路径运行。");
        } else {
            System.err.println("Usage: java FilterEvHouseholds <person_household_map.csv> <households_full.csv> <ev_households.csv>");
            System.exit(1);
            return;
        }

        ensureFileExists(mappingFile, "EV mapping");
        ensureFileExists(householdFile, "households csv");

        Set<String> evHouseholdIds = loadEvHouseholdIds(mappingFile);
        HouseholdDataset dataset = loadHouseholdDataset(householdFile);

        writeFilteredHouseholds(dataset, evHouseholdIds, outputFile);
    }

    private static void ensureFileExists(Path path, String label) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("找不到 " + label + " 文件: " + path);
        }
    }

    private static Set<String> loadEvHouseholdIds(Path mappingFile) throws IOException {
        List<String> lines = Files.readAllLines(mappingFile, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("EV mapping CSV 为空: " + mappingFile);
        }
        Set<String> householdIds = new HashSet<>();
        for (int i = 1; i < lines.size(); i++) { // 跳过表头
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> cells = parseCsvLine(line);
            if (cells.size() < 2) {
                throw new IllegalArgumentException("EV mapping 第 " + (i + 1) + " 行缺少 householdId: " + line);
            }
            String householdId = cells.get(1).trim();
            if (!householdId.isEmpty()) {
                householdIds.add(householdId);
            }
        }
        System.out.println("[INFO] EV household 总数 = " + householdIds.size());
        return householdIds;
    }

    private static HouseholdDataset loadHouseholdDataset(Path csvPath) throws IOException {
        List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("household CSV 为空: " + csvPath);
        }
        List<String> header = parseCsvLine(lines.get(0));
        int idIdx = findColumn(header, "householdid", "household_id", "hhid", "id");
        int dwellingIdx = findColumn(header, "dwelling_type", "dwellingtype", "type");
        if (idIdx < 0) {
            throw new IllegalArgumentException("CSV 表头需包含 householdId 列。实际表头: " + header);
        }
        if (dwellingIdx < 0) {
            throw new IllegalArgumentException("CSV 表头需包含 dwelling_type 列。实际表头: " + header);
        }

        List<HouseholdRow> rows = new ArrayList<>();
        Map<String, HouseholdRow> rowsById = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> cells = parseCsvLine(line);
            if (cells.size() <= Math.max(idIdx, dwellingIdx)) {
                throw new IllegalArgumentException("household CSV 第 " + (i + 1) + " 行列数不足: " + line);
            }
            String householdId = cells.get(idIdx).trim();
            HouseholdRow row = new HouseholdRow(householdId, cells);
            HouseholdRow previous = rowsById.put(householdId, row);
            if (previous != null) {
                System.out.println("[WARN] Duplicate householdId '" + householdId + "'，保留首个记录。");
                continue;
            }
            rows.add(row);
        }
        return new HouseholdDataset(header, dwellingIdx, rows);
    }

    private static void writeFilteredHouseholds(HouseholdDataset dataset,
                                                Set<String> evHouseholdIds,
                                                Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        Random random = new Random(PV_RANDOM_SEED);
        List<String> header = new ArrayList<>(dataset.header);
        header.add(PV_COLUMN_NAME);

        Set<String> unmatchedIds = new HashSet<>(evHouseholdIds);

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writeCsvLine(writer, header);
            for (HouseholdRow row : dataset.rows) {
                if (!evHouseholdIds.contains(row.householdId)) {
                    continue;
                }
                boolean hasPv = computePvFlag(row, dataset.dwellingTypeIdx, random);
                List<String> out = new ArrayList<>(row.cells);
                out.add(Boolean.toString(hasPv));
                writeCsvLine(writer, out);
                unmatchedIds.remove(row.householdId);
            }
        }

        if (!unmatchedIds.isEmpty()) {
            System.out.println("[WARN] EV household 在 CSV 中缺失: " + unmatchedIds.size());
            if (unmatchedIds.size() <= 20) {
                System.out.println("       缺失列表: " + unmatchedIds);
            }
        } else {
            System.out.println("[INFO] 所有 EV household 均成功写入。");
        }
    }

    private static boolean computePvFlag(HouseholdRow row, int dwellingIdx, Random random) {
        String dwelling = dwellingIdx < row.cells.size() ? row.cells.get(dwellingIdx).trim() : "";
        String normalized = dwelling.toLowerCase(Locale.ROOT);
        if (normalized.contains("apartment")) {
            return false;
        }
        if (normalized.contains("house") || normalized.contains("townhouse")) {
            return random.nextDouble() < PV_PROBABILITY;
        }
        if (!normalized.isEmpty()) {
            System.out.println("[WARN] 未识别的 dwelling_type '" + dwelling + "'，默认视为无光伏。");
        }
        return false;
    }

    private static void writeCsvLine(BufferedWriter writer, List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(escapeCsv(cells.get(i)));
        }
        writer.newLine();
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        if (!needQuotes) {
            return value;
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
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

    private static double initPvProbability(double defaultValue) {
        String value = System.getProperty("ev.household.pvProb");
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (parsed < 0.0 || parsed > 1.0) {
                throw new IllegalArgumentException("光伏概率需在 0 与 1 之间: " + parsed);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析光伏概率参数 ev.household.pvProb=" + value, e);
        }
    }

    private static long initPvSeed(long defaultSeed) {
        String value = System.getProperty("ev.household.pvSeed");
        if (value == null || value.isBlank()) {
            return defaultSeed;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析光伏随机种子参数 ev.household.pvSeed=" + value, e);
        }
    }

    private static class HouseholdDataset {
        final List<String> header;
        final int dwellingTypeIdx;
        final List<HouseholdRow> rows;

        HouseholdDataset(List<String> header,
                         int dwellingTypeIdx,
                         List<HouseholdRow> rows) {
            this.header = header;
            this.dwellingTypeIdx = dwellingTypeIdx;
            this.rows = rows;
        }
    }

    private static class HouseholdRow {
        final String householdId;
        final List<String> cells;

        HouseholdRow(String householdId, List<String> cells) {
            this.householdId = householdId;
            this.cells = cells;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof HouseholdRow)) return false;
            HouseholdRow that = (HouseholdRow) o;
            return Objects.equals(householdId, that.householdId) && Objects.equals(cells, that.cells);
        }

        @Override
        public int hashCode() {
            return Objects.hash(householdId, cells);
        }
    }
}

