package org.matsim.contrib.ev.behavior;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import jakarta.annotation.Nullable;

/**
 * Iteration-end calibration that nudges {@code eta_g} towards the target charging frequencies
 * using the simulated frequencies observed during the iteration.
 */
@Singleton
public final class ChargingBehaviourCalibrationListener implements IterationEndsListener {

	private static final Logger LOG = LogManager.getLogger(ChargingBehaviourCalibrationListener.class);

	private final ChargingBehaviourModel model;
	private final OutputDirectoryHierarchy controlerIO;
	private final int startCalibrationIteration;
	private boolean calibrationEnabled;

	@Inject
	public ChargingBehaviourCalibrationListener(@Nullable ChargingBehaviourModel model, Config config,
			OutputDirectoryHierarchy controlerIO) {
		this.model = model;
		ChargingBehaviourConfigGroup cfg = ConfigUtils.addOrGetModule(config,
				ChargingBehaviourConfigGroup.GROUP_NAME, ChargingBehaviourConfigGroup.class);
		this.startCalibrationIteration = cfg.getStartCalibrationIteration();
		this.calibrationEnabled = startCalibrationIteration <= 0;
		this.controlerIO = controlerIO;
		if (this.model != null) {
			this.model.setLearningRateIterationOffset(this.startCalibrationIteration);
		}
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		if (model == null) {
			return;
		}

		if (!calibrationEnabled && event.getIteration() >= startCalibrationIteration) {
			calibrationEnabled = true;
			LOG.info("Iteration {}: enabling charging behaviour calibration (start iteration={}).",
					event.getIteration(), startCalibrationIteration);
		}

		if (!calibrationEnabled) {
			LOG.trace("Iteration {}: calibration disabled (start iteration={}), skipping eta update.",
					event.getIteration(), startCalibrationIteration);
			model.resetSimulationStats();
			return;
		}

		List<ChargingBehaviourModel.CalibrationEntry> iterationEntries = new ArrayList<>();

		for (GroupType group : GroupType.values()) {
			double target = model.getTargetRealFrequency(group);
			ChargingBehaviourModel.CalibrationEntry entry = model.updateEta(group, target, event.getIteration());
			if (entry == null) {
				continue;
			}
			iterationEntries.add(entry);
			if (entry.updated()) {
				LOG.debug(
						"Iteration {}: eta update for {} (pRealTot={}, pSimTot={}, alpha={}, qTarget={}, qSim={}, learningRate={}, delta={}, etaBefore={}, etaAfter={})",
						entry.iteration(), group, entry.pRealTotal(), entry.pSimTotal(), entry.alpha(), entry.qTarget(),
						entry.qSim(), entry.learningRate(), entry.delta(), entry.etaBefore(), entry.etaAfter());
			} else {
				LOG.debug(
						"Iteration {}: eta update skipped for {} (pRealTot={}, pSimTot={}, alpha={}, qTarget={}, qSim={}, learningRate={}, etaBefore={})",
						entry.iteration(), group, entry.pRealTotal(), entry.pSimTotal(), entry.alpha(), entry.qTarget(),
						entry.qSim(), entry.learningRate(), entry.etaBefore());
			}
		}

		if (!iterationEntries.isEmpty()) {
			writeCalibrationReport(event.getIteration(), iterationEntries);
		}

		model.resetSimulationStats();
	}

	public void enableCalibration() {
		if (!calibrationEnabled) {
			calibrationEnabled = true;
			LOG.info("Charging behaviour calibration manually enabled.");
		}
	}

	@SuppressWarnings("deprecation")
	private void writeCalibrationReport(int iteration, List<ChargingBehaviourModel.CalibrationEntry> entries) {
		String file = controlerIO.getIterationFilename(iteration, "chargingEtaCalibration.csv");
		CSVFormat format = CSVFormat.DEFAULT.builder()
				.setDelimiter(';')
				.setHeader("iteration", "group", "pRealTotal", "pSimTotal", "alpha", "qTarget", "qSim", "learningRate",
						"delta", "etaBefore", "etaAfter", "updated")
				.build();

		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(Paths.get(file)), format)) {
			for (ChargingBehaviourModel.CalibrationEntry entry : entries) {
				printer.printRecord(entry.iteration(), entry.group().name(), entry.pRealTotal(), entry.pSimTotal(),
						entry.alpha(), entry.qTarget(), entry.qSim(), entry.learningRate(), entry.delta(),
						entry.etaBefore(), entry.etaAfter(), entry.updated());
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to write charging calibration report to " + file, e);
		}
	}
}

