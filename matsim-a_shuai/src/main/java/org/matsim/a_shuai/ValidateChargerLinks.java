package org.matsim.a_shuai;

import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.ev.infrastructure.ChargerReader;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructureSpecification;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructureUtils;
import org.matsim.core.network.io.MatsimNetworkReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 校验 chargers 文件中的每个 charger 的 link 是否存在于给定的 network。
 *
 * Usage:
 *   java org.matsim.a_shuai.ValidateChargerLinks <network.xml> <chargers.xml>
 * 若省略参数，使用脚本内置的默认路径。
 */
public class ValidateChargerLinks {

    private static final boolean USE_HARDCODED_PATHS = true;
    private static final String DEFAULT_NETWORK = "/Users/S4065267/Matsim_ev/Simulation/networkCleaned.xml";
    private static final String DEFAULT_CHARGERS = "/Users/S4065267/Matsim_ev/Simulation/100/chargers_0.71.xml";

    public static void main(String[] args) {
        final Path networkPath;
        final Path chargersPath;

        if (args.length >= 2) {
            networkPath = Paths.get(args[0]);
            chargersPath = Paths.get(args[1]);
        } else if (USE_HARDCODED_PATHS) {
            networkPath = Paths.get(DEFAULT_NETWORK);
            chargersPath = Paths.get(DEFAULT_CHARGERS);
            System.out.println("[INFO] 未提供参数，使用默认路径。");
        } else {
            System.err.println("Usage: java org.matsim.a_shuai.ValidateChargerLinks <network.xml> <chargers.xml>");
            return;
        }

        if (!Files.exists(networkPath)) {
            System.err.println("network 文件不存在: " + networkPath);
            return;
        }
        if (!Files.exists(chargersPath)) {
            System.err.println("chargers 文件不存在: " + chargersPath);
            return;
        }

        Network network = org.matsim.core.network.NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(networkPath.toString());

        ChargingInfrastructureSpecification infra = ChargingInfrastructureUtils.createChargingInfrastructureSpecification();
        new ChargerReader(infra).readFile(chargersPath.toString());

        AtomicInteger missingLink = new AtomicInteger(0);
        AtomicInteger ok = new AtomicInteger(0);

        infra.getChargerSpecifications().values().forEach(spec -> {
            if (spec.getLinkId() == null || network.getLinks().get(spec.getLinkId()) == null) {
                missingLink.incrementAndGet();
                System.out.printf(Locale.US, "[WARN] charger %s 缺少或找不到 linkId=%s%n",
                        spec.getId(), spec.getLinkId());
            } else {
                ok.incrementAndGet();
            }
        });

        System.out.printf(Locale.US,
                "[DONE] 校验完成：有效 chargers=%d，缺失/无效 link 的 chargers=%d%n",
                ok.get(), missingLink.get());
    }
}

