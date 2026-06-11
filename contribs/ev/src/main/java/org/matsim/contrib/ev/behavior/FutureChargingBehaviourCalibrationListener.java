package org.matsim.contrib.ev.behavior;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import jakarta.annotation.Nullable;

/**
 * Iteration-end calibration for {@link FutureChargingBehaviourModel}.
 */
@Singleton
public final class FutureChargingBehaviourCalibrationListener implements IterationEndsListener {
	private final FutureChargingBehaviourModel model;
	private final OutputDirectoryHierarchy controlerIO;
	private final int startCalibrationIteration;
	private final String latentPublicDemandFile;
	private boolean calibrationEnabled;

	@Inject
	public FutureChargingBehaviourCalibrationListener(@Nullable FutureChargingBehaviourModel model, Config config,
			OutputDirectoryHierarchy controlerIO) {
		this.model = model;
		FutureChargingBehaviourConfigGroup cfg = ConfigUtils.addOrGetModule(config,
			FutureChargingBehaviourConfigGroup.GROUP_NAME, FutureChargingBehaviourConfigGroup.class);
		this.startCalibrationIteration = cfg.getStartCalibrationIteration();
		this.latentPublicDemandFile = cfg.getLatentPublicDemandFile();
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
			writeLatentPublicDemand(event.getIteration());
			writeLatentDemandDiagnostics(event.getIteration());
			if (!calibrationEnabled && event.getIteration() >= startCalibrationIteration) {
				calibrationEnabled = true;
		}
		if (!calibrationEnabled) {
			model.resetSimulationStats();
			model.resetLatentDemandDiagnostics();
			return;
		}

		List<FutureChargingBehaviourModel.CalibrationEntry> entries = new ArrayList<>();
		for (GroupType group : GroupType.values()) {
			FutureChargingBehaviourModel.CalibrationEntry entry = model.updateEta(group,
					model.getTargetRealFrequency(group), event.getIteration());
			if (entry != null) {
				entries.add(entry);
			}
		}
		if (!entries.isEmpty()) {
			writeCalibrationReport(event.getIteration(), entries);
		}
		model.resetSimulationStats();
		model.resetLatentDemandDiagnostics();
	}

	@SuppressWarnings("deprecation")
	private void writeCalibrationReport(int iteration,
			List<FutureChargingBehaviourModel.CalibrationEntry> entries) {
		String file = controlerIO.getIterationFilename(iteration, "futureChargingEtaCalibration.csv");
		CSVFormat format = CSVFormat.DEFAULT.builder()
				.setDelimiter(';')
				.setHeader("iteration", "group", "pRealTotal", "pSimTotal", "alpha", "qTarget", "qSim",
						"learningRate", "delta", "etaBefore", "etaAfter", "updated")
				.build();

		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(Paths.get(file)), format)) {
			for (FutureChargingBehaviourModel.CalibrationEntry entry : entries) {
				printer.printRecord(entry.iteration(), entry.group().name(), entry.pRealTotal(), entry.pSimTotal(),
						entry.alpha(), entry.qTarget(), entry.qSim(), entry.learningRate(), entry.delta(),
						entry.etaBefore(), entry.etaAfter(), entry.updated());
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to write future charging calibration report to " + file, e);
		}
	}

	@SuppressWarnings("deprecation")
	private void writeLatentPublicDemand(int iteration) {
		List<FutureChargingBehaviourModel.LatentPublicDemandRecord> records = model.consumeLatentPublicDemandRecords();
		if (records.isEmpty()) {
			return;
		}
			String file = controlerIO.getIterationFilename(iteration, latentPublicDemandFile);
			CSVFormat format = CSVFormat.DEFAULT.builder()
					.setDelimiter(';')
					.setHeader("iteration", "personId", "vehicleId", "group", "activityLabel", "activityType", "linkId",
							"time", "timeBand", "soc", "probability", "mandatory", "supplyType", "opportunityValue",
							"demandType", "socUpdated", "demandDuration", "energyDemand", "chargingDuration",
							"chargedEnergy", "socAfterCharging")
					.build();

		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(Paths.get(file)), format)) {
				for (FutureChargingBehaviourModel.LatentPublicDemandRecord record : records) {
					printer.printRecord(iteration, record.personId(), record.vehicleId(), record.group().name(),
							record.activityLabel().name(), record.activityType(), record.linkId(), record.time(),
							record.timeBand().name(), record.soc(), record.probability(), record.mandatory(),
							record.supplyType().name(), record.opportunityValue(), record.demandType(),
							record.socUpdated(), record.demandDuration(), record.energyDemand(), record.chargingDuration(),
							record.chargedEnergy(), record.socAfterCharging());
				}
		} catch (IOException e) {
			throw new RuntimeException("Failed to write future latent public charging demand to " + file, e);
		}
	}

	@SuppressWarnings("deprecation")
	private void writeLatentDemandDiagnostics(int iteration) {
		List<FutureChargingBehaviourModel.LatentDemandDiagnosticEntry> entries =
				model.getLatentDemandDiagnosticEntries(iteration);
		if (entries.isEmpty()) {
			return;
		}
		String file = controlerIO.getIterationFilename(iteration, "futureChargingLatentDemandDiagnostics.csv");
		CSVFormat format = CSVFormat.DEFAULT.builder()
				.setDelimiter(';')
				.setHeader("iteration", "group", "population", "nonHomeCandidatePersons", "eligibleNonHomePersons",
						"preferredSelectedPersons", "preferredReachedPersons", "decisionEvaluatedPersons",
						"demandGeneratedPersons", "mandatoryDemandPersons", "conditionalDemandPersons",
						"socUpdatedPersons")
				.build();

		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(Paths.get(file)), format)) {
			for (FutureChargingBehaviourModel.LatentDemandDiagnosticEntry entry : entries) {
				printer.printRecord(entry.iteration(), entry.group().name(), entry.population(),
						entry.nonHomeCandidatePersons(), entry.eligibleNonHomePersons(),
						entry.preferredSelectedPersons(), entry.preferredReachedPersons(),
						entry.decisionEvaluatedPersons(), entry.demandGeneratedPersons(),
						entry.mandatoryDemandPersons(), entry.conditionalDemandPersons(), entry.socUpdatedPersons());
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to write future latent demand diagnostics to " + file, e);
		}
	}
}
