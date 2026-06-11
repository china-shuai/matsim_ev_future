package org.matsim.a_shuai;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Builds Paper 3 LDF EV-driver plans from the pipeline CSV output.
 */
public class BuildPaper3LdfPlans {
	private static final String DEFAULT_PIPELINE_DIR = "/Users/S4065267/Simulation _paper3/pipeline_outputs_output10";
	private static final String DEFAULT_OUTPUT = "/Users/S4065267/Simulation _paper3/matsim_input_10/plans_ldf.xml";
	private static final double PV_PROBABILITY = initProbability("paper3.population.pvProbability", 0.8);
	private static final double APARTMENT_HOME_CHARGER_RATE = initProbability("paper3.population.apartmentHomeChargerRate", 0.30);
	private static final long HOUSEHOLD_RANDOM_SEED = initSeed("paper3.population.householdSeed", 2025L);

	public static void main(String[] args) throws IOException {
		Path pipelineDir = args.length > 0 ? Paths.get(args[0]) : Paths.get(DEFAULT_PIPELINE_DIR);
		Path output = args.length > 1 ? Paths.get(args[1]) : Paths.get(DEFAULT_OUTPUT);
		String assignment = args.length > 2 ? args[2].trim() : "LDF";

		Path personCsv = pipelineDir.resolve("ev_population_person.csv");
		Path householdCsv = pipelineDir.resolve("ev_population_household.csv");
		Path planCsv = pipelineDir.resolve("final_plan_ev_" + assignment + ".csv");
		ensureFileExists(personCsv, "EV population person CSV");
		ensureFileExists(householdCsv, "EV population household CSV");
		ensureFileExists(planCsv, "LDF final plan CSV");

		Map<String, HouseholdInfo> households = loadHouseholds(householdCsv);
		Set<String> evDrivers = loadEvDriverIds(personCsv, "ev_driver_" + assignment);
		Map<String, List<ActivityRow>> rowsByAgent = loadPlanRows(planCsv, evDrivers);
		PopulationStats stats = writePopulation(rowsByAgent, households, output);

		System.out.println("Paper 3 LDF plans written: " + output);
		System.out.println("assignment: " + assignment);
		System.out.println("ev_driver_" + assignment + " ids: " + evDrivers.size());
		System.out.println("agents with plans: " + rowsByAgent.size());
		System.out.println("activities written: " + rowsByAgent.values().stream().mapToInt(List::size).sum());
		System.out.println("dwelling APARTMENT: " + stats.apartments);
		System.out.println("dwelling HOUSE_WITH_PV: " + stats.houseWithPv);
		System.out.println("dwelling HOUSE_NO_PV: " + stats.houseNoPv);
		System.out.println("home charger true: " + stats.homeChargerTrue);
		System.out.println("missing household attributes: " + stats.missingHousehold);
	}

	private static Map<String, HouseholdInfo> loadHouseholds(Path householdCsv) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(householdCsv, StandardCharsets.UTF_8)) {
			String headerLine = reader.readLine();
			if (headerLine == null) {
				throw new IllegalArgumentException("CSV is empty: " + householdCsv);
			}

			List<String> header = parseCsvLine(headerLine);
			int householdIdx = findColumn(header, "HouseholdId");
			int dwellingIdx = findColumn(header, "dwelling_type");
			if (householdIdx < 0 || dwellingIdx < 0) {
				throw new IllegalArgumentException("Missing HouseholdId or dwelling_type in " + householdCsv);
			}

			Random pvRandom = new Random(HOUSEHOLD_RANDOM_SEED);
			Random chargerRandom = new Random(HOUSEHOLD_RANDOM_SEED + 1);
			Map<String, HouseholdInfo> result = new LinkedHashMap<>();
			String line;
			int lineNumber = 1;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isBlank()) {
					continue;
				}
				List<String> cells = parseCsvLine(line);
				if (cells.size() <= Math.max(householdIdx, dwellingIdx)) {
					throw new IllegalArgumentException("Missing household columns at line " + lineNumber + ": " + line);
				}

				String householdId = cells.get(householdIdx).trim();
				String dwelling = cells.get(dwellingIdx).trim();
				String group = determineGroupType(dwelling, pvRandom);
				boolean hasHomeCharger = determineHomeCharger(group, chargerRandom);
				result.putIfAbsent(householdId, new HouseholdInfo(group, hasHomeCharger));
			}
			return result;
		}
	}

	private static Set<String> loadEvDriverIds(Path personCsv, String evDriverColumn) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(personCsv, StandardCharsets.UTF_8)) {
			String headerLine = reader.readLine();
			if (headerLine == null) {
				throw new IllegalArgumentException("CSV is empty: " + personCsv);
			}

			List<String> header = parseCsvLine(headerLine);
			int agentIdx = findColumn(header, "AgentId");
			int evDriverIdx = findColumn(header, evDriverColumn);
			if (agentIdx < 0 || evDriverIdx < 0) {
				throw new IllegalArgumentException("Missing AgentId or " + evDriverColumn + " in " + personCsv);
			}

			Set<String> result = new LinkedHashSet<>();
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				List<String> cells = parseCsvLine(line);
				if (cells.size() <= Math.max(agentIdx, evDriverIdx)) {
					continue;
				}
				if (parseBoolean(cells.get(evDriverIdx))) {
					result.add(cells.get(agentIdx).trim());
				}
			}
			return result;
		}
	}

	private static Map<String, List<ActivityRow>> loadPlanRows(Path planCsv, Set<String> evDrivers) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(planCsv, StandardCharsets.UTF_8)) {
			String headerLine = reader.readLine();
			if (headerLine == null) {
				throw new IllegalArgumentException("CSV is empty: " + planCsv);
			}

			List<String> header = parseCsvLine(headerLine);
			int activityIdx = findColumn(header, "Activity");
			int agentIdx = findColumn(header, "AgentId");
			int householdIdx = findColumn(header, "HouseholdId");
			int arrivingModeIdx = findColumn(header, "ArrivingMode");
			int xIdx = findColumn(header, "x");
			int yIdx = findColumn(header, "y");
			int startIdx = findColumn(header, "act_start_hhmmss");
			int endIdx = findColumn(header, "act_end_hhmmss");
			if (activityIdx < 0 || agentIdx < 0 || householdIdx < 0 || arrivingModeIdx < 0
					|| xIdx < 0 || yIdx < 0 || startIdx < 0 || endIdx < 0) {
				throw new IllegalArgumentException("Missing required columns in " + planCsv + ": " + header);
			}

			Map<String, List<ActivityRow>> rowsByAgent = new LinkedHashMap<>();
			String line;
			int lineNumber = 1;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isBlank()) {
					continue;
				}
				List<String> cells = parseCsvLine(line);
				if (cells.size() <= Math.max(endIdx, Math.max(xIdx, yIdx))) {
					continue;
				}

				String agentId = cells.get(agentIdx).trim();
				if (!evDrivers.contains(agentId)) {
					continue;
				}

				String activityType = cells.get(activityIdx).trim();
				String arrivingMode = normalizeMode(cells.get(arrivingModeIdx));
				String householdId = cells.get(householdIdx).trim();

				Coord coord = normalizeCoord(cells.get(xIdx), cells.get(yIdx), lineNumber);
				String startTime = normalizeTime(cells.get(startIdx), lineNumber);
				String endTime = normalizeTime(cells.get(endIdx), lineNumber);

				rowsByAgent.computeIfAbsent(agentId, id -> new ArrayList<>())
						.add(new ActivityRow(activityType, arrivingMode, householdId, coord.x, coord.y, startTime, endTime));
			}
			return rowsByAgent;
		}
	}

	private static PopulationStats writePopulation(Map<String, List<ActivityRow>> rowsByAgent,
			Map<String, HouseholdInfo> households, Path output) throws IOException {
		Files.createDirectories(output.toAbsolutePath().getParent());
		PopulationStats stats = new PopulationStats();
		try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
			writer.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
			writer.write("<!DOCTYPE population SYSTEM \"http://www.matsim.org/files/dtd/population_v6.dtd\">\n\n");
			writer.write("<population>\n");

			for (Map.Entry<String, List<ActivityRow>> entry : rowsByAgent.entrySet()) {
				String agentId = entry.getKey();
				List<ActivityRow> rows = entry.getValue();
				if (rows.isEmpty()) {
					continue;
				}
				HouseholdInfo householdInfo = households.get(rows.get(0).householdId);
				if (householdInfo == null) {
					householdInfo = new HouseholdInfo("HOUSE_NO_PV", true);
					stats.missingHousehold++;
				}
				stats.record(householdInfo);

				writer.write("\t<person id=\"");
				writer.write(xml(agentId));
				writer.write("\">\n");
				writer.write("\t\t<attributes>\n");
				writeAttribute(writer, "wevc:active", "java.lang.Boolean", "true");
				writeAttribute(writer, "sevc:criticalSoc", "java.lang.Double", "0.2");
				writeAttribute(writer, "vehicles", "org.matsim.vehicles.PersonVehicles",
						"{\"car\":\"" + agentId + "\"}");
				writeAttribute(writer, "ev:dwellingType", "java.lang.String", householdInfo.groupType);
				writeAttribute(writer, "ev:hasHomeCharger", "java.lang.Boolean",
						Boolean.toString(householdInfo.hasHomeCharger));
				writer.write("\t\t</attributes>\n");
				writer.write("\t\t<plan selected=\"yes\">\n");

				for (int i = 0; i < rows.size(); i++) {
					ActivityRow row = rows.get(i);
					if (i > 0) {
						writer.write("\t\t\t<leg mode=\"");
						writer.write(xml(row.arrivingMode));
						writer.write("\" />\n");
					}
					writer.write("\t\t\t<activity type=\"");
					writer.write(xml(row.activityType));
					writer.write("\" x=\"");
					writer.write(row.x);
					writer.write("\" y=\"");
					writer.write(row.y);
					writer.write("\" start_time=\"");
					writer.write(row.startTime);
					writer.write("\" end_time=\"");
					writer.write(row.endTime);
					writer.write("\" />\n");
				}

				writer.write("\t\t</plan>\n");
				writer.write("\t</person>\n");
			}

			writer.write("</population>\n");
		}
		return stats;
	}

	private static String determineGroupType(String dwelling, Random pvRandom) {
		String normalized = dwelling == null ? "" : dwelling.trim().toLowerCase(Locale.ROOT);
		if (normalized.contains("apartment")) {
			return "APARTMENT";
		}
		if (normalized.contains("house") || normalized.contains("townhouse")) {
			return pvRandom.nextDouble() < PV_PROBABILITY ? "HOUSE_WITH_PV" : "HOUSE_NO_PV";
		}
		return "HOUSE_NO_PV";
	}

	private static boolean determineHomeCharger(String groupType, Random chargerRandom) {
		if ("APARTMENT".equals(groupType)) {
			return chargerRandom.nextDouble() < APARTMENT_HOME_CHARGER_RATE;
		}
		return true;
	}

	private static void writeAttribute(BufferedWriter writer, String name, String clazz, String value) throws IOException {
		writer.write("\t\t\t<attribute name=\"");
		writer.write(xml(name));
		writer.write("\" class=\"");
		writer.write(xml(clazz));
		writer.write("\">");
		writer.write(xml(value));
		writer.write("</attribute>\n");
	}

	private static void ensureFileExists(Path path, String label) {
		if (!Files.exists(path)) {
			throw new IllegalArgumentException("Missing " + label + ": " + path);
		}
	}

	private static String normalizeMode(String value) {
		String mode = value == null ? "" : value.trim();
		if (mode.isEmpty() || mode.equalsIgnoreCase("NA")) {
			return "car";
		}
		return mode.toLowerCase(Locale.ROOT);
	}

	private static Coord normalizeCoord(String csvX, String csvY, int lineNumber) {
		double xValue = parseCoordinate(csvX, "x", lineNumber);
		double yValue = parseCoordinate(csvY, "y", lineNumber);
		if (xValue > 1_000_000.0 && yValue < 1_000_000.0) {
			return new Coord(trimDouble(yValue), trimDouble(xValue));
		}
		return new Coord(trimDouble(xValue), trimDouble(yValue));
	}

	private static double parseCoordinate(String value, String label, int lineNumber) {
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Cannot parse " + label + " at line " + lineNumber + ": " + value, e);
		}
	}

	private static String trimDouble(double value) {
		if (value == Math.rint(value)) {
			return Long.toString(Math.round(value));
		}
		return Double.toString(value);
	}

	private static String normalizeTime(String value, int lineNumber) {
		String text = value == null ? "" : value.trim();
		if (text.isEmpty() || text.equalsIgnoreCase("NA")) {
			throw new IllegalArgumentException("Missing activity time at line " + lineNumber);
		}
		String[] parts = text.split(":");
		if (parts.length != 3) {
			throw new IllegalArgumentException("Invalid time at line " + lineNumber + ": " + value);
		}
		int hours = Integer.parseInt(parts[0]);
		int minutes = Integer.parseInt(parts[1]);
		double seconds = Double.parseDouble(parts[2]);
		if (hours < 0 || minutes < 0 || minutes >= 60 || seconds < 0 || seconds >= 60) {
			throw new IllegalArgumentException("Invalid time at line " + lineNumber + ": " + value);
		}
		return text;
	}

	private static boolean parseBoolean(String value) {
		String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
		return text.equals("true") || text.equals("1") || text.equals("yes") || text.equals("y");
	}

	private static double initProbability(String property, double defaultValue) {
		String value = System.getProperty(property);
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		double parsed = Double.parseDouble(value);
		if (parsed < 0.0 || parsed > 1.0) {
			throw new IllegalArgumentException(property + " must be in [0, 1]: " + parsed);
		}
		return parsed;
	}

	private static long initSeed(String property, long defaultValue) {
		String value = System.getProperty(property);
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		return Long.parseLong(value);
	}

	private static int findColumn(List<String> header, String name) {
		for (int i = 0; i < header.size(); i++) {
			if (header.get(i).trim().equalsIgnoreCase(name)) {
				return i;
			}
		}
		return -1;
	}

	private static List<String> parseCsvLine(String line) {
		List<String> result = new ArrayList<>();
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
				result.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		result.add(current.toString());
		return result;
	}

	private static String xml(String value) {
		return value.replace("&", "&amp;")
				.replace("\"", "&quot;")
				.replace("<", "&lt;")
				.replace(">", "&gt;");
	}

	private record ActivityRow(String activityType, String arrivingMode, String householdId, String x, String y,
			String startTime, String endTime) {
	}

	private record HouseholdInfo(String groupType, boolean hasHomeCharger) {
	}

	private record Coord(String x, String y) {
	}

	private static class PopulationStats {
		int apartments;
		int houseWithPv;
		int houseNoPv;
		int homeChargerTrue;
		int missingHousehold;

		void record(HouseholdInfo info) {
			switch (info.groupType) {
				case "APARTMENT" -> apartments++;
				case "HOUSE_WITH_PV" -> houseWithPv++;
				case "HOUSE_NO_PV" -> houseNoPv++;
				default -> {
				}
			}
			if (info.hasHomeCharger) {
				homeChargerTrue++;
			}
		}
	}
}
