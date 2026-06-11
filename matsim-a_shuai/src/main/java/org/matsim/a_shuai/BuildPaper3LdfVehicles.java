package org.matsim.a_shuai;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Builds Paper 3 LDF EV vehicles for the agents contained in the generated plans.
 */
public class BuildPaper3LdfVehicles {
	private static final String DEFAULT_PLANS = "/Users/S4065267/Simulation _paper3/matsim_input_10/plans.xml";
	private static final String DEFAULT_OUTPUT = "/Users/S4065267/Simulation _paper3/matsim_input_10/ev_vehicles_ldf.xml";
	private static final String DEFAULT_TEMPLATE = "/Users/S4065267/Matsim_ev/Simulation/10/ev_vehicles_random_soc.xml";

	private static final String EV_VEHICLE_TYPE = "EV_105.0kWh";
	private static final String TEMPLATE_EV_VEHICLE_TYPE = "EV_65.0kWh";
	private static final double BATTERY_CAPACITY_KWH = 105.0;
	private static final double SOC_MEAN = initDouble("paper3.vehicle.socMean", 0.6);
	private static final double SOC_SD = initDouble("paper3.vehicle.socSd", 0.1);
	private static final double SOC_MIN = initProbability("paper3.vehicle.socMin", 0.3);
	private static final double SOC_MAX = initProbability("paper3.vehicle.socMax", 1.0);
	private static final long SOC_RANDOM_SEED = initSeed("paper3.vehicle.socSeed", 2025L);

	public static void main(String[] args) throws IOException, XMLStreamException {
		Path plans = args.length > 0 ? Paths.get(args[0]) : Paths.get(DEFAULT_PLANS);
		Path output = args.length > 1 ? Paths.get(args[1]) : Paths.get(DEFAULT_OUTPUT);
		Path template = args.length > 2 ? Paths.get(args[2]) : Paths.get(DEFAULT_TEMPLATE);

		ensureFileExists(plans, "Paper 3 LDF plans XML");
		ensureFileExists(template, "vehicle type template XML");

		Set<String> vehicleIds = readPersonIds(plans);
		writeVehicles(vehicleIds, template, output);

		System.out.println("Paper 3 LDF vehicles written: " + output);
		System.out.println("vehicles written: " + vehicleIds.size());
		System.out.println("vehicle type: " + EV_VEHICLE_TYPE);
		System.out.println("battery capacity kWh: " + BATTERY_CAPACITY_KWH);
		System.out.println("initial SOC distribution: normal(mean=" + SOC_MEAN + ", sd=" + SOC_SD
				+ "), clipped to [" + SOC_MIN + ", " + SOC_MAX + "], seed=" + SOC_RANDOM_SEED);
	}

	private static Set<String> readPersonIds(Path plans) throws IOException, XMLStreamException {
		XMLInputFactory factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

		Set<String> result = new LinkedHashSet<>();
		try (InputStream input = Files.newInputStream(plans)) {
			XMLStreamReader reader = factory.createXMLStreamReader(input);
			try {
				while (reader.hasNext()) {
					if (reader.next() == XMLStreamConstants.START_ELEMENT && "person".equals(reader.getLocalName())) {
						String id = reader.getAttributeValue(null, "id");
						if (id != null && !id.isBlank()) {
							result.add(id.trim());
						}
					}
				}
			} finally {
				reader.close();
			}
		}
		return result;
	}

	private static void writeVehicles(Set<String> vehicleIds, Path template, Path output) throws IOException {
		Files.createDirectories(output.toAbsolutePath().getParent());
		try (BufferedReader reader = Files.newBufferedReader(template, StandardCharsets.UTF_8);
				BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
			String line;
			boolean foundExistingVehicleSection = false;
			while ((line = reader.readLine()) != null) {
				if (line.trim().startsWith("<vehicle id=")) {
					foundExistingVehicleSection = true;
					break;
				}
				writer.write(transformVehicleTypeLine(line));
				writer.write("\n");
			}
			if (!foundExistingVehicleSection) {
				throw new IllegalArgumentException("Vehicle template contains no <vehicle id=...> entries: " + template);
			}

			Random socRandom = new Random(SOC_RANDOM_SEED);
			for (String vehicleId : vehicleIds) {
				double initialSoc = sampleInitialSoc(socRandom);
				writer.write("\t<vehicle id=\"");
				writer.write(xml(vehicleId));
				writer.write("\" type=\"");
				writer.write(EV_VEHICLE_TYPE);
				writer.write("\">\n");
				writer.write("\t\t<attributes>\n");
				writeAttribute(writer, "initialSoc", "java.lang.Double", Double.toString(initialSoc));
				writer.write("\t\t</attributes>\n");
				writer.write("\t</vehicle>\n");
			}
			writer.write("\t\t\n\n</vehicleDefinitions>\n");
		}
	}

	private static String transformVehicleTypeLine(String line) {
		String transformed = line.replace(TEMPLATE_EV_VEHICLE_TYPE, EV_VEHICLE_TYPE);
		if (transformed.contains("energyCapacityInKWhOrLiters")) {
			transformed = transformed.replace(">65.0<", ">" + BATTERY_CAPACITY_KWH + "<");
		}
		return transformed;
	}

	private static void writeAttribute(BufferedWriter writer, String name, String clazz, String value) throws IOException {
		writer.write("\t\t\t<attribute name=\"");
		writer.write(xml(name));
		writer.write("\" class=\"");
		writer.write(xml(clazz));
		writer.write("\">");
		writer.write(xmlText(value));
		writer.write("</attribute>\n");
	}

	private static void ensureFileExists(Path path, String label) {
		if (!Files.exists(path)) {
			throw new IllegalArgumentException("Missing " + label + ": " + path);
		}
	}

	private static double sampleInitialSoc(Random random) {
		double soc = SOC_MEAN + SOC_SD * random.nextGaussian();
		return Math.max(SOC_MIN, Math.min(SOC_MAX, soc));
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

	private static double initDouble(String property, double defaultValue) {
		String value = System.getProperty(property);
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		return Double.parseDouble(value);
	}

	private static long initSeed(String property, long defaultValue) {
		String value = System.getProperty(property);
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		return Long.parseLong(value);
	}

	private static String xml(String value) {
		return value.replace("&", "&amp;")
				.replace("\"", "&quot;")
				.replace("<", "&lt;")
				.replace(">", "&gt;");
	}

	private static String xmlText(String value) {
		return value.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;");
	}
}
