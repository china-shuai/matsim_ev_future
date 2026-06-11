package org.matsim.a_shuai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批量将 EV 车辆文件中的 initialSoc 属性设为固定值（默认 0.3）。
 *
 * <p>Usage:
 * <pre>
 *   java org.matsim.a_shuai.SetEvInitialSoc inputVehicles.xml outputVehicles.xml [soc]
 * </pre>
 *
 * <p>如果未提供参数，则使用脚本内置的默认路径，并就地覆盖输出。
 * 可通过 JVM 参数 {@code -Dev.vehicle.initialSoc=} 覆盖默认目标值。
 */
public class SetEvInitialSoc {

    private static final boolean USE_HARDCODED_PATHS = true;
    private static final String DEFAULT_VEHICLES_IN = "/Users/S4065267/Matsim_ev/Simulation/ev_vehicles.xml";
    private static final String DEFAULT_VEHICLES_OUT = "/Users/S4065267/Matsim_ev/Simulation/ev_vehicles0.3.xml";

    private static final double TARGET_SOC = initTargetSoc(0.3);

    public static void main(String[] args) throws IOException {
        final Path vehiclesIn;
        final Path vehiclesOut;
        final double targetSoc;

        if (args.length >= 2) {
            vehiclesIn = Paths.get(args[0]);
            vehiclesOut = Paths.get(args[1]);
            targetSoc = args.length >= 3 ? Double.parseDouble(args[2]) : TARGET_SOC;
        } else if (USE_HARDCODED_PATHS) {
            vehiclesIn = Paths.get(DEFAULT_VEHICLES_IN);
            vehiclesOut = Paths.get(DEFAULT_VEHICLES_OUT);
            targetSoc = TARGET_SOC;
            System.out.println("[INFO] 未提供参数，使用默认路径，并将初始电量设为 " + targetSoc);
        } else {
            System.err.println("Usage: java org.matsim.a_shuai.SetEvInitialSoc <vehicles_in.xml> <vehicles_out.xml> [soc]");
            System.exit(1);
            return;
        }

        ensureFileExists(vehiclesIn, "vehicles input");
        if (vehiclesOut.getParent() != null) {
            Files.createDirectories(vehiclesOut.getParent());
        }

        String original = Files.readString(vehiclesIn, StandardCharsets.UTF_8);

        Pattern pattern = Pattern.compile("(?s)(<vehicle\\s+[^>]*>\\s*<attributes>\\s*<attribute\\s+name=\"initialSoc\"[^>]*>)([^<]+)(</attribute>)");
        Matcher matcher = pattern.matcher(original);

        StringBuffer sb = new StringBuffer();
        int updated = 0;

        while (matcher.find()) {
            matcher.appendReplacement(sb, matcher.group(1) + targetSoc + matcher.group(3));
            updated++;
        }
        matcher.appendTail(sb);

        if (updated == 0) {
            System.out.println("[WARN] 未在 <vehicle> 块中找到任何 initialSoc 属性，未对文件做修改。");
        } else {
            Files.writeString(vehiclesOut, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("[DONE] 已将 " + updated + " 个 initialSoc 属性更新为 " + targetSoc);
        }
    }

    private static double initTargetSoc(double defaultValue) {
        String value = System.getProperty("ev.vehicle.initialSoc");
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (parsed < 0.0 || parsed > 1.0) {
                throw new IllegalArgumentException("初始电量需在 0 与 1 之间: " + parsed);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析初始电量参数 ev.vehicle.initialSoc=" + value, e);
        }
    }

    private static void ensureFileExists(Path path, String label) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("找不到 " + label + " 文件: " + path);
        }
    }
}

