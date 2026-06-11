package org.matsim.a_shuai;

import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Random;

/**
 * 根据正态分布（均值 0.7、标准差 0.133）为车辆文件中的每辆车分配 initialSoc 属性，限制在 [0.4, 1.0] 范围内。
 * <p>Usage:</p>
 * <pre>
 * java org.matsim.a_shuai.AssignInitialSocFromDistribution vehicles_in.xml vehicles_out.xml
 * </pre>
 */
public class AssignInitialSocFromDistribution {

    private static final boolean USE_HARDCODED_PATHS = true;
    private static final String DEFAULT_VEHICLES_IN = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/ev_vehicles.xml";
    private static final String DEFAULT_VEHICLES_OUT = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/simulation/ev_vehicles_random_soc.xml";

    private static final double SOC_MIN = 0.3;
    private static final double SOC_MAX = 0.9;
    private static final double SOC_MEAN = 0.6;
    private static final double SOC_STD = 0.175; // roughly (1.0-0.4)/4

    private static final double DEFAULT_SEED = 2025L;

    public static void main(String[] args) throws IOException {
        final Path vehiclesIn;
        final Path vehiclesOut;
        final long seed = initSeed((long) DEFAULT_SEED);

        if (args.length >= 2) {
            vehiclesIn = Paths.get(args[0]);
            vehiclesOut = Paths.get(args[1]);
        } else if (USE_HARDCODED_PATHS) {
            vehiclesIn = Paths.get(DEFAULT_VEHICLES_IN);
            vehiclesOut = Paths.get(DEFAULT_VEHICLES_OUT);
            System.out.println("[INFO] 未提供参数，使用默认路径。");
        } else {
            System.err.println("Usage: java org.matsim.a_shuai.AssignInitialSocFromDistribution <vehicles_in.xml> <vehicles_out.xml>");
            return;
        }

        if (!Files.exists(vehiclesIn)) {
            System.err.println("[ERROR] 车辆输入文件不存在: " + vehiclesIn);
            return;
        }
        if (vehiclesOut.getParent() != null) {
            Files.createDirectories(vehiclesOut.getParent());
        }

        Vehicles vehicles = VehicleUtils.createVehiclesContainer();
        new MatsimVehicleReader(vehicles).readFile(vehiclesIn.toString());

        Random random = new Random(seed);
        int updated = 0;

        for (Vehicle vehicle : vehicles.getVehicles().values()) {
            double soc = sampleSoc(random);
            vehicle.getAttributes().putAttribute("initialSoc", soc);
            updated++;
        }

        new MatsimVehicleWriter(vehicles).writeFile(vehiclesOut.toString());
        System.out.printf(Locale.US, "[DONE] 已为 %d 辆车辆分配初始电量，结果写入: %s%n", updated, vehiclesOut);
    }

    private static double sampleSoc(Random random) {
        double value = SOC_MEAN + random.nextGaussian() * SOC_STD;
        if (value < SOC_MIN) {
            value = SOC_MIN;
        } else if (value > SOC_MAX) {
            value = SOC_MAX;
        }
        return value;
    }

    private static long initSeed(long defaultSeed) {
        String value = System.getProperty("ev.population.socSeed");
        if (value == null || value.isBlank()) {
            return defaultSeed;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析随机种子参数 ev.population.socSeed=" + value, e);
        }
    }
}

