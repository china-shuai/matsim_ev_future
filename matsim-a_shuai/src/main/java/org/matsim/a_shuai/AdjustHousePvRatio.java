package org.matsim.a_shuai;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/**
 * 调整户型中 house 的 PV 占比：在 HOUSE_WITH_PV 与 HOUSE_NO_PV 之间按指定比例重采样。
 * 仅处理 dwellingType 为 HOUSE_WITH_PV 或 HOUSE_NO_PV 的 agent，其它类型保持不变。
 *
 * Usage:
 *   java org.matsim.a_shuai.AdjustHousePvRatio <population_in> <population_out> <target_pv_ratio>
 * 若省略参数使用默认路径和比例。
 * 可通过 JVM -Dev.house.pvRatio=0.6 设定目标比例（覆盖命令行/默认）。
 */
public class AdjustHousePvRatio {

    private static final boolean USE_HARDCODED_PATHS = true;
    private static final String DEFAULT_POP_IN = "/Users/S4065267/Matsim_ev/Simulation/100/population_ev_no_soc_attrs_0.3.xml";
    private static final String DEFAULT_POP_OUT = "/Users/S4065267/Matsim_ev/Simulation/100/population_ev_house_pv_adjusted.xml";
    private static final double DEFAULT_TARGET_RATIO = 0.9; // HOUSE_WITH_PV / (HOUSE_WITH_PV + HOUSE_NO_PV)
    private static final long DEFAULT_SEED = 2025L;

    private static final String ATTR_DWELLING = "ev:dwellingType";
    private static final String TYPE_WITH_PV = "HOUSE_WITH_PV";
    private static final String TYPE_NO_PV = "HOUSE_NO_PV";

    public static void main(String[] args) throws Exception {
        final Path popIn;
        final Path popOut;
        final double targetRatio;
        final long seed = DEFAULT_SEED;

        if (args.length >= 3) {
            popIn = Paths.get(args[0]);
            popOut = Paths.get(args[1]);
            targetRatio = Double.parseDouble(args[2]);
        } else if (USE_HARDCODED_PATHS) {
            popIn = Paths.get(DEFAULT_POP_IN);
            popOut = Paths.get(DEFAULT_POP_OUT);
            targetRatio = DEFAULT_TARGET_RATIO;
            System.out.println("[INFO] 未提供参数，使用默认路径和目标比例 " + targetRatio);
        } else {
            System.err.println("Usage: java org.matsim.a_shuai.AdjustHousePvRatio <population_in> <population_out> <target_pv_ratio>");
            return;
        }

        double ratio = overrideRatio(targetRatio);
        if (ratio < 0 || ratio > 1) {
            throw new IllegalArgumentException("目标比例需在 0-1 之间: " + ratio);
        }

        if (!Files.exists(popIn)) {
            throw new IllegalArgumentException("population 输入不存在: " + popIn);
        }
        if (popOut.getParent() != null) {
            Files.createDirectories(popOut.getParent());
        }

        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        new PopulationReader(scenario).readFile(popIn.toString());

        List<Person> withPv = new ArrayList<>();
        List<Person> noPv = new ArrayList<>();

        scenario.getPopulation().getPersons().values().forEach(person -> {
            String type = getDwelling(person);
            if (TYPE_WITH_PV.equals(type)) {
                withPv.add(person);
            } else if (TYPE_NO_PV.equals(type)) {
                noPv.add(person);
            }
        });

        int totalHouse = withPv.size() + noPv.size();
        if (totalHouse == 0) {
            System.out.println("[WARN] 未找到 house 户型，未做修改。");
            new PopulationWriter(scenario.getPopulation()).write(popOut.toString());
            return;
        }

        int targetWithPv = (int) Math.round(ratio * totalHouse);
        targetWithPv = Math.min(totalHouse, Math.max(0, targetWithPv));

        Random random = new Random(seed);
        // 先把全部 house 设为 NO_PV
        withPv.forEach(p -> p.getAttributes().putAttribute(ATTR_DWELLING, TYPE_NO_PV));

        // 需要设置为 WITH_PV 的数量
        int needWithPv = targetWithPv;

        List<Person> candidates = new ArrayList<>();
        candidates.addAll(withPv);
        candidates.addAll(noPv);

        // 随机选择 needWithPv 个设为 WITH_PV
        java.util.Collections.shuffle(candidates, random);
        for (int i = 0; i < needWithPv && i < candidates.size(); i++) {
            candidates.get(i).getAttributes().putAttribute(ATTR_DWELLING, TYPE_WITH_PV);
        }

        new PopulationWriter(scenario.getPopulation()).write(popOut.toString());

        System.out.printf(Locale.US,
                "[DONE] house 总数=%d, 目标 PV 比例=%.2f, 实际 withPV=%d, noPV=%d, 输出: %s%n",
                totalHouse, ratio,
                countType(scenario, TYPE_WITH_PV),
                countType(scenario, TYPE_NO_PV),
                popOut);
    }

    private static String getDwelling(Person person) {
        Object attr = person.getAttributes().getAttribute(ATTR_DWELLING);
        return attr == null ? "" : attr.toString();
    }

    private static long countType(Scenario scenario, String type) {
        return scenario.getPopulation().getPersons().values().stream()
                .filter(Objects::nonNull)
                .filter(p -> type.equals(getDwelling(p)))
                .count();
    }

    private static double overrideRatio(double base) {
        String v = System.getProperty("house.pvRatio");
        if (v == null || v.isBlank()) return base;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析 house.pvRatio=" + v, e);
        }
    }
}

