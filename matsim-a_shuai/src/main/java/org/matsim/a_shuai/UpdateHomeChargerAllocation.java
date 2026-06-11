package org.matsim.a_shuai;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.ev.infrastructure.ChargerWriter;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructureSpecification;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructureUtils;
import org.matsim.contrib.ev.infrastructure.ChargerReader;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * 调整 apartment 住户的家充拥有率（其他 dwelling type 恒有家充），并同步更新 chargers 文件：
 * - population: 写入/覆盖 person attribute {@code ev:hasHomeCharger}
 * - chargers: 仅保留对应 person 仍有家充的私有充电桩（通过属性 sevc:persons 关联）
 *
 * Usage:
 *   java org.matsim.a_shuai.UpdateHomeChargerAllocation <population_in> <chargers_in> <population_out> <chargers_out> [apartmentHasChargerRate]
 *
 * 若省略参数，使用内置默认路径；可通过 JVM 参数 -Dev.population.apartmentHasChargerRate 覆盖默认比例。
 */
public class UpdateHomeChargerAllocation {

    private static final boolean USE_HARDCODED_PATHS = true;
    private static final String DEFAULT_POPULATION_IN = "/Users/S4065267/Matsim_ev/Simulation/100/population_ev_house_pv_adjusted.xml";
    private static final String DEFAULT_CHARGERS_IN   = "/Users/S4065267/Matsim_ev/Simulation/100/chargers.xml";
    private static final String DEFAULT_POPULATION_OUT = "/Users/S4065267/Matsim_ev/Simulation/100/population0.9HC.xml";
    private static final String DEFAULT_CHARGERS_OUT   = "/Users/S4065267/Matsim_ev/Simulation/100/chargers_0.9.xml";

    private static final String ATTR_DWELLING = "ev:dwellingType";
    private static final String ATTR_HOME_CHARGER = "ev:hasHomeCharger";
    private static final String CHARGER_PERSON_ATTR = "sevc:persons";

    private static final double DEFAULT_APARTMENT_HAS_CHARGER_RATE = initApartmentHasChargerRate(0.9);
    private static final long DEFAULT_SEED = 2025L;

    public static void main(String[] args) {
        final Path popIn;
        final Path chargersIn;
        final Path popOut;
        final Path chargersOut;

        final double apartmentRate;
        final long seed = DEFAULT_SEED;

        if (args.length >= 4) {
            popIn = Paths.get(args[0]);
            chargersIn = Paths.get(args[1]);
            popOut = Paths.get(args[2]);
            chargersOut = Paths.get(args[3]);
            apartmentRate = args.length >= 5 ? Double.parseDouble(args[4]) : DEFAULT_APARTMENT_HAS_CHARGER_RATE;
        } else if (USE_HARDCODED_PATHS) {
            popIn = Paths.get(DEFAULT_POPULATION_IN);
            chargersIn = Paths.get(DEFAULT_CHARGERS_IN);
            popOut = Paths.get(DEFAULT_POPULATION_OUT);
            chargersOut = Paths.get(DEFAULT_CHARGERS_OUT);
            System.out.println("[INFO] 未提供参数，使用默认路径。");
            apartmentRate = DEFAULT_APARTMENT_HAS_CHARGER_RATE;
        } else {
            System.err.println("Usage: java org.matsim.a_shuai.UpdateHomeChargerAllocation <population_in> <chargers_in> <population_out> <chargers_out> [apartmentHasChargerRate]");
            return;
        }

        if (!Files.exists(popIn)) {
            System.err.println("population 文件不存在: " + popIn);
            return;
        }
        if (!Files.exists(chargersIn)) {
            System.err.println("chargers 文件不存在: " + chargersIn);
            return;
        }
        try {
            if (popOut.getParent() != null) Files.createDirectories(popOut.getParent());
            if (chargersOut.getParent() != null) Files.createDirectories(chargersOut.getParent());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        System.out.printf(Locale.US, "[INFO] apartment 有家充比例设为 %.2f%%%n", apartmentRate * 100);

        Random random = new Random(seed);

        // 1) 读取并更新 population
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        new PopulationReader(scenario).readFile(popIn.toString());

        Set<String> personsWithHomeCharger = new HashSet<>();
        final int[] apartments = {0};
        final int[] apartmentsWithCharger = {0};

        scenario.getPopulation().getPersons().values().forEach(person -> {
            if (person == null) {
                return;
            }
            String dwelling = getDwelling(person);
            boolean isApartment = dwelling.toLowerCase(Locale.ROOT).contains("apartment");
            boolean hasCharger = true;
            if (isApartment) {
                apartments[0]++;
                hasCharger = random.nextDouble() < apartmentRate;
                if (hasCharger) apartmentsWithCharger[0]++;
            }
            person.getAttributes().putAttribute(ATTR_HOME_CHARGER, hasCharger);
            if (hasCharger) {
                personsWithHomeCharger.add(person.getId().toString());
            }
        });

        new PopulationWriter(scenario.getPopulation()).write(popOut.toString());

        // 2) 读取 chargers 并过滤掉不再需要的私有桩
        ChargingInfrastructureSpecification infra = ChargingInfrastructureUtils.createChargingInfrastructureSpecification();
        new ChargerReader(infra).readFile(chargersIn.toString());

        ChargingInfrastructureSpecification filtered = ChargingInfrastructureUtils.createChargingInfrastructureSpecification();
        infra.getChargerSpecifications().values().forEach(spec -> {
            Object attr = spec.getAttributes().getAttribute(CHARGER_PERSON_ATTR);
            // 保留公共桩（无 sevc:persons）；仅移除不再有家充的私有桩
            if (attr == null || personsWithHomeCharger.contains(attr.toString())) {
                filtered.addChargerSpecification(spec);
            }
        });

        new ChargerWriter(filtered.getChargerSpecifications().values().stream()).write(chargersOut.toString());

        System.out.println("[DONE] population 写出: " + popOut);
        System.out.println("[DONE] chargers 写出: " + chargersOut);
        System.out.println("[INFO] apartment 总数: " + apartments[0] + "，其中保留家充: " + apartmentsWithCharger[0]);
        System.out.println("[INFO] 总家充人数: " + personsWithHomeCharger.size());
        System.out.println("[INFO] 保留的私人充电桩: " + filtered.getChargerSpecifications().size());
    }

    private static String getDwelling(Person person) {
        Object attr = person.getAttributes().getAttribute(ATTR_DWELLING);
        if (attr == null) return "";
        String v = attr.toString().trim();
        return v.isEmpty() ? "" : v;
    }

    private static double initApartmentHasChargerRate(double defaultValue) {
        String value = System.getProperty("ev.population.apartmentHasChargerRate");
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (parsed < 0.0 || parsed > 1.0) {
                throw new IllegalArgumentException("apartment 有家充比例需在 0-1 之间: " + parsed);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析 apartment 有家充比例: " + value, e);
        }
    }
}

