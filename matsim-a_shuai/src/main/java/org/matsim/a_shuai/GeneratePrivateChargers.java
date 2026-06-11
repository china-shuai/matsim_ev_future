package org.matsim.a_shuai;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.ev.infrastructure.Charger;
import org.matsim.contrib.ev.infrastructure.ChargerSpecification;
import org.matsim.contrib.ev.infrastructure.ChargerWriter;
import org.matsim.contrib.ev.infrastructure.ChargerReader;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructureSpecification;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructureUtils;
import org.matsim.contrib.ev.infrastructure.ImmutableChargerSpecification;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 根据 population 中 ev:hasHomeCharger=true 的人员，在 network 上找到最近的 link，
 * 为每人生成一个私人充电桩（属性 sevc:persons）。
 *
 * Usage:
 *   java org.matsim.a_shuai.GeneratePrivateChargers <network.xml> <population.xml> <chargers_in.xml> <chargers_out.xml>
 *   java org.matsim.a_shuai.GeneratePrivateChargers <network.xml> <population.xml> <chargers_out.xml>
 * 若省略参数，使用脚本内置的默认路径。
 */
public class GeneratePrivateChargers {

    private static final boolean USE_HARDCODED_PATHS = true;
    private static final String DEFAULT_NETWORK = "/Users/S4065267/Matsim_ev/Simulation/networkCleaned.xml";
    private static final String DEFAULT_POPULATION = "/Users/S4065267/Matsim_ev/Simulation/100/population0.9HC.xml";
    private static final String DEFAULT_CHARGERS_IN = "/Users/S4065267/Matsim_ev/Simulation/100/chargers_0.9.xml";
    private static final String DEFAULT_CHARGERS_OUT = "/Users/S4065267/Matsim_ev/Simulation/100/chargers_0.91.xml";

    private static final String ATTR_HOME_CHARGER = "ev:hasHomeCharger";
    private static final String CHARGER_PERSON_ATTR = "sevc:persons";
    private static final double DEFAULT_PLUG_POWER_W = 10000; // 10 kW
    private static final int DEFAULT_PLUG_COUNT = 1;
    private static final String DEFAULT_CHARGER_TYPE = "type1";

    public static void main(String[] args) {
        final Path networkPath;
        final Path populationPath;
        final Path chargersInPath;
        final Path chargersOutPath;

        if (args.length >= 4) {
            networkPath = Paths.get(args[0]);
            populationPath = Paths.get(args[1]);
            chargersInPath = Paths.get(args[2]);
            chargersOutPath = Paths.get(args[3]);
        } else if (args.length == 3) {
            networkPath = Paths.get(args[0]);
            populationPath = Paths.get(args[1]);
            chargersInPath = null;
            chargersOutPath = Paths.get(args[2]);
        } else if (USE_HARDCODED_PATHS) {
            networkPath = Paths.get(DEFAULT_NETWORK);
            populationPath = Paths.get(DEFAULT_POPULATION);
            chargersInPath = Paths.get(DEFAULT_CHARGERS_IN);
            chargersOutPath = Paths.get(DEFAULT_CHARGERS_OUT);
            System.out.println("[INFO] 未提供参数，使用默认路径。");
        } else {
            System.err.println("Usage: java org.matsim.a_shuai.GeneratePrivateChargers <network.xml> <population.xml> <chargers_in.xml> <chargers_out.xml>");
            return;
        }

        if (!Files.exists(networkPath)) {
            System.err.println("network 文件不存在: " + networkPath);
            return;
        }
        if (!Files.exists(populationPath)) {
            System.err.println("population 文件不存在: " + populationPath);
            return;
        }
        if (chargersInPath != null && !Files.exists(chargersInPath)) {
            System.err.println("chargers 输入文件不存在: " + chargersInPath);
            return;
        }
        try {
            if (chargersOutPath.getParent() != null) Files.createDirectories(chargersOutPath.getParent());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        new MatsimNetworkReader(scenario.getNetwork()).readFile(networkPath.toString());
        new PopulationReader(scenario).readFile(populationPath.toString());

        Network network = scenario.getNetwork();
        QuadTree<Link> carLinkQuadTree = buildCarLinkQuadTree(network);

        ChargingInfrastructureSpecification infra = ChargingInfrastructureUtils.createChargingInfrastructureSpecification();
        if (chargersInPath != null) {
            // 读取现有 chargers（移除 DOCTYPE，避免拉取远程 DTD），保留公共桩（无 sevc:persons）
            ChargingInfrastructureSpecification existing = ChargingInfrastructureUtils.createChargingInfrastructureSpecification();
            ChargerReader reader = new ChargerReader(existing);
            Path chargersToRead = stripDoctypeIfNeeded(chargersInPath);
            reader.setValidating(false); // 禁用校验
            reader.readFile(chargersToRead.toString());

            existing.getChargerSpecifications().values().forEach(spec -> {
                Object attr = spec.getAttributes().getAttribute(CHARGER_PERSON_ATTR);
                if (attr == null) {
                    infra.addChargerSpecification(spec);
                }
            });
        }

        AtomicInteger idx = new AtomicInteger(0);
        AtomicInteger totalHomeCharger = new AtomicInteger(0);
        AtomicInteger missingHomeCoord = new AtomicInteger(0);

        scenario.getPopulation().getPersons().values().forEach(person -> {
            if (person == null) {
                return;
            }
            Object attr = person.getAttributes().getAttribute(ATTR_HOME_CHARGER);
            if (!(attr instanceof Boolean) || !((Boolean) attr)) {
                return; // 无家充
            }
            totalHomeCharger.incrementAndGet();

            Coord homeCoord = findHomeCoord(person);
            if (homeCoord == null) {
                missingHomeCoord.incrementAndGet();
                System.out.printf(Locale.US,
                        "[WARN] person %s 缺少 home 坐标，跳过家充生成%n",
                        person.getId());
                return;
            }
            Link nearest = findNearestCarLink(carLinkQuadTree, homeCoord);
            if (nearest == null) {
                missingHomeCoord.incrementAndGet();
                System.out.printf(Locale.US,
                        "[WARN] person %s 的 home 坐标(%.3f, %.3f) 找不到合适的机动车 link（过滤非数字/pt 前缀），跳过家充生成%n",
                        person.getId(), homeCoord.getX(), homeCoord.getY());
                return;
            }

            AttributesImpl attrs = new AttributesImpl();
            attrs.putAttribute(CHARGER_PERSON_ATTR, person.getId().toString());

            ChargerSpecification spec = ImmutableChargerSpecification.newBuilder()
                    .id(Id.create("privateCharger_" + idx.getAndIncrement(), Charger.class))
                    .linkId(nearest.getId())
                    .chargerType(DEFAULT_CHARGER_TYPE)
                    .plugCount(DEFAULT_PLUG_COUNT)
                    .plugPower(DEFAULT_PLUG_POWER_W)
                    .attributes(attrs)
                    .build();

            infra.addChargerSpecification(spec);
        });

        new ChargerWriter(infra.getChargerSpecifications().values().stream())
                .write(chargersOutPath.toString());

        System.out.printf(Locale.US,
                "[DONE] 已处理家充标记人数: %d，缺少 Home 坐标/Link 跳过: %d，生成/保留充电桩: %d，输出: %s%n",
                totalHomeCharger.get(), missingHomeCoord.get(), infra.getChargerSpecifications().size(), chargersOutPath);
    }

    private static Coord findHomeCoord(Person person) {
        if (person.getSelectedPlan() == null) {
            return null;
        }
        return person.getSelectedPlan().getPlanElements().stream()
                .filter(pe -> pe instanceof Activity)
                .map(pe -> (Activity) pe)
                .filter(a -> {
                    String type = a.getType();
                    return type != null && type.toLowerCase(Locale.ROOT).contains("home");
                })
                .findFirst()
                .map(Activity::getCoord)
                .orElse(null);
    }

    private static QuadTree<Link> buildCarLinkQuadTree(Network network) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Link link : network.getLinks().values()) {
            if (link == null) continue;
            if (!isCarLinkCandidate(link)) continue;
            minX = Math.min(minX, Math.min(link.getFromNode().getCoord().getX(), link.getToNode().getCoord().getX()));
            minY = Math.min(minY, Math.min(link.getFromNode().getCoord().getY(), link.getToNode().getCoord().getY()));
            maxX = Math.max(maxX, Math.max(link.getFromNode().getCoord().getX(), link.getToNode().getCoord().getX()));
            maxY = Math.max(maxY, Math.max(link.getFromNode().getCoord().getY(), link.getToNode().getCoord().getY()));
        }
        QuadTree<Link> qt = new QuadTree<>(minX, minY, maxX, maxY);
        for (Link link : network.getLinks().values()) {
            if (link == null) continue;
            if (!isCarLinkCandidate(link)) continue;
            Coord mid = CoordUtils.minus(link.getToNode().getCoord(), link.getFromNode().getCoord());
            mid = new Coord(link.getFromNode().getCoord().getX() + 0.5 * mid.getX(),
                    link.getFromNode().getCoord().getY() + 0.5 * mid.getY());
            qt.put(mid.getX(), mid.getY(), link);
        }
        return qt;
    }

    private static Link findNearestCarLink(QuadTree<Link> quadTree, Coord coord) {
        if (quadTree == null) return null;
        return quadTree.getClosest(coord.getX(), coord.getY());
    }

    private static boolean isCarLinkCandidate(Link link) {
        if (link == null || link.getFromNode() == null || link.getToNode() == null) return false;
        String id = link.getId().toString();
        if (!id.matches("\\d+")) {
            return false; // 仅接受纯数字 linkId，过滤 pt 前缀等
        }
        // 需包含 car 模式（若未设置 modes，视为可用）
        var modes = link.getAllowedModes();
        return modes == null || modes.isEmpty() || modes.contains("car");
    }

    private static Path stripDoctypeIfNeeded(Path original) {
        try {
            String content = Files.readString(original, StandardCharsets.UTF_8);
            if (!content.contains("<!DOCTYPE")) {
                return original;
            }
            String sanitized = content.replaceAll("(?is)<!DOCTYPE[^>]*>", "");
            Path tmp = Files.createTempFile("chargers_no_dtd", ".xml");
            Files.writeString(tmp, sanitized, StandardCharsets.UTF_8);
            tmp.toFile().deleteOnExit();
            System.out.println("[INFO] 已移除 chargers DOCTYPE 以避免远程 DTD 访问: " + tmp);
            return tmp;
        } catch (IOException e) {
            throw new RuntimeException("处理 chargers 文件时出错: " + original, e);
        }
    }
}
