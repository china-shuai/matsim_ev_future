package org.matsim.contrib.ev.withinday.stats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.ev.behavior.GroupType;
import org.matsim.contrib.ev.behavior.LocationType;
import org.matsim.contrib.ev.behavior.TimeBand;
import org.matsim.contrib.ev.withinday.WithinDayEvEngine;
import org.matsim.core.controler.IterationCounter;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.vehicles.Vehicle;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Captures every charging decision evaluated by {@link WithinDayEvEngine},
 * together with the resulting outcome (success or various failure reasons).
 * A CSV report is written for each iteration.
 */
@Singleton
public final class ChargingDecisionCollector implements IterationEndsListener {

	public enum Outcome {
		SUCCESS,
		MODEL_DECLINED,
		ALREADY_CHARGED_TODAY,
		NO_CHARGER_ON_LINK,
		NO_MATCHING_CHARGER_TYPE,
		NO_EV_REGISTERED
	}

	private static final Logger LOG = LogManager.getLogger(ChargingDecisionCollector.class);

	private static final class Entry {
		private final Id<Person> personId;
		private final Id<Vehicle> vehicleId;
		private final Id<Link> linkId;
		private final double time;
		private final LocationType locationType;
		private final String requestedChargerType;
		private final Outcome outcome;
		private final GroupType groupType;
		private final boolean hasHomeCharger;
		private final double probability;
		private final boolean willCharge;
		private final boolean completed;
		private final String failureReason;

		private Entry(Id<Person> personId, Id<Vehicle> vehicleId, Id<Link> linkId, double time,
				LocationType locationType, String requestedChargerType, Outcome outcome, GroupType groupType,
				boolean hasHomeCharger, double probability, boolean willCharge, boolean completed,
				String failureReason) {
			this.personId = personId;
			this.vehicleId = vehicleId;
			this.linkId = linkId;
			this.time = time;
			this.locationType = locationType;
			this.requestedChargerType = requestedChargerType;
			this.outcome = outcome;
			this.groupType = groupType;
			this.hasHomeCharger = hasHomeCharger;
			this.probability = probability;
			this.willCharge = willCharge;
			this.completed = completed;
			this.failureReason = failureReason;
		}
	}

	private final OutputDirectoryHierarchy controlerIO;
	private final IterationCounter iterationCounter;
	private final List<Entry> entries = new ArrayList<>();
	private final Map<GroupType, Set<Id<Person>>> registeredPersonsByGroup = new EnumMap<>(GroupType.class);
	private final Map<GroupType, Set<Id<Person>>> decisionParticipantsByGroup = new EnumMap<>(GroupType.class);
	private final Map<GroupType, Set<Id<Person>>> chargersByGroup = new EnumMap<>(GroupType.class);
	private final Map<GroupType, Set<Id<Person>>> willingPersonsByGroup = new EnumMap<>(GroupType.class);
	private final Map<GroupType, Set<Id<Person>>> failedWillingPersonsByGroup = new EnumMap<>(GroupType.class);
	private final Map<GroupType, EnumMap<TimeBand, Integer>> successTimeDistribution = new EnumMap<>(GroupType.class);

	@Inject
	ChargingDecisionCollector(OutputDirectoryHierarchy controlerIO, IterationCounter iterationCounter) {
		this.controlerIO = controlerIO;
		this.iterationCounter = iterationCounter;
		for (GroupType group : GroupType.values()) {
			registeredPersonsByGroup.put(group, new HashSet<>());
			decisionParticipantsByGroup.put(group, new HashSet<>());
			chargersByGroup.put(group, new HashSet<>());
			willingPersonsByGroup.put(group, new HashSet<>());
			failedWillingPersonsByGroup.put(group, new HashSet<>());
			EnumMap<TimeBand, Integer> bandCounts = new EnumMap<>(TimeBand.class);
			for (TimeBand band : TimeBand.values()) {
				bandCounts.put(band, 0);
			}
			successTimeDistribution.put(group, bandCounts);
		}
	}

	public void registerPotentialParticipant(Id<Person> personId, GroupType groupType) {
		if (personId != null) {
			registeredPersonsByGroup.get(groupType).add(personId);
		}
	}

	public void record(Id<Person> personId, Id<Vehicle> vehicleId, Id<Link> linkId, double time,
			LocationType locationType, String requestedChargerType, double probability, boolean willCharge,
			Outcome outcome, GroupType groupType, boolean hasHomeCharger, String failureDetail) {
		boolean completed = outcome == Outcome.SUCCESS;
		String failureReason = failureDetail;
		if (failureReason == null && willCharge && !completed) {
			failureReason = outcome.name();
		}
		if (failureReason == null) {
			failureReason = "";
		}

		entries.add(new Entry(personId, vehicleId, linkId, time, locationType, requestedChargerType, outcome,
				groupType, hasHomeCharger, probability, willCharge, completed, failureReason));

		if (personId != null) {
			decisionParticipantsByGroup.get(groupType).add(personId);
			if (willCharge) {
				willingPersonsByGroup.get(groupType).add(personId);
				if (!completed) {
					failedWillingPersonsByGroup.get(groupType).add(personId);
				}
			}
			if (completed) {
				chargersByGroup.get(groupType).add(personId);
				failedWillingPersonsByGroup.get(groupType).remove(personId);
				TimeBand band = determineTimeBand(time);
				successTimeDistribution.get(groupType).merge(band, 1, Integer::sum);
			}
		}
	}

	@Override
	@SuppressWarnings("deprecation")
	public void notifyIterationEnds(IterationEndsEvent event) {
		if (entries.isEmpty()) {
			return;
		}

		var file = controlerIO.getIterationFilename(iterationCounter.getIterationNumber(),
				"chargingDecisions.csv");

		CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(';').setHeader("personId", "vehicleId", "linkId",
				"time_s", "locationType", "requestedChargerType", "dwellingType", "hasHomeCharger", "probability",
				"willCharge", "completed", "outcome", "failureReason").build();

		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(Paths.get(file)), format)) {
			for (Entry entry : entries) {
				printer.printRecord(entry.personId, entry.vehicleId, entry.linkId, entry.time,
						entry.locationType.name(), entry.requestedChargerType, entry.groupType.name(),
						entry.hasHomeCharger, entry.probability, entry.willCharge, entry.completed,
						entry.outcome.name(), entry.failureReason);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to write charging decision statistics to " + file, e);
		} finally {
			writeSummaryFiles(event);
			entries.clear();
			resetAggregates();
		}
	}

	private void writeSummaryFiles(IterationEndsEvent event) {
		writeGroupSummary();
		writeTimeDistribution();
		logSummary(event);
	}

	private void writeGroupSummary() {
		var file = controlerIO.getIterationFilename(iterationCounter.getIterationNumber(),
				"chargingDecisionSummary.csv");

		CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(';').setHeader("groupType", "participants",
				"willChargePersons", "uniqueChargers", "willChargeRate", "successRate", "successGivenWillCharge",
				"failedWillChargePersons").build();

		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(Paths.get(file)), format)) {
			for (GroupType group : GroupType.values()) {
				int participants = registeredPersonsByGroup.get(group).size();
				int chargers = chargersByGroup.get(group).size();
				int willing = willingPersonsByGroup.get(group).size();
				int failed = failedWillingPersonsByGroup.get(group).size();

				double willChargeRate = participants == 0 ? 0.0 : ((double) willing) / participants;
				double successRate = participants == 0 ? 0.0 : ((double) chargers) / participants;
				double successGivenWill = willing == 0 ? 0.0 : ((double) chargers) / willing;

				printer.printRecord(group.name(), participants, willing, chargers, willChargeRate, successRate,
						successGivenWill, failed);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to write charging decision summary to " + file, e);
		}
	}

	private void writeTimeDistribution() {
		var file = controlerIO.getIterationFilename(iterationCounter.getIterationNumber(),
				"chargingStartTimeDistribution.csv");

		CSVFormat format = CSVFormat.DEFAULT.builder()
				.setDelimiter(';')
				.setHeader("groupType", "timeBand", "successCount")
				.build();

		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(Paths.get(file)), format)) {
			for (GroupType group : GroupType.values()) {
				for (TimeBand band : TimeBand.values()) {
					int count = successTimeDistribution.get(group).get(band);
					printer.printRecord(group.name(), band.name(), count);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to write charging time distribution to " + file, e);
		}
	}

	private void logSummary(IterationEndsEvent event) {
		StringBuilder summary = new StringBuilder();
		for (GroupType group : GroupType.values()) {
			int willing = willingPersonsByGroup.get(group).size();
			int success = chargersByGroup.get(group).size();
			summary.append(group.name()).append("[will=").append(willing).append(", success=").append(success)
					.append("] ");
		}

		LOG.info("Iteration {}: charging decisions per group -> {}", event.getIteration(), summary.toString());
	}

	private void resetAggregates() {
		for (GroupType group : GroupType.values()) {
			registeredPersonsByGroup.get(group).clear();
			decisionParticipantsByGroup.get(group).clear();
			chargersByGroup.get(group).clear();
			willingPersonsByGroup.get(group).clear();
			failedWillingPersonsByGroup.get(group).clear();
			for (TimeBand band : TimeBand.values()) {
				successTimeDistribution.get(group).put(band, 0);
			}
		}
	}

	private static TimeBand determineTimeBand(double timeSeconds) {
		double value = timeSeconds % (24 * 3600.0);
		if (value < 0) {
			value += 24 * 3600.0;
		}
		double hour = value / 3600.0;

		if (hour >= 6.0 && hour < 10.0) {
			return TimeBand.MORNING_6_10;
		} else if (hour >= 10.0 && hour < 16.0) {
			return TimeBand.MIDDAY_10_16;
		} else if (hour >= 16.0 && hour < 22.0) {
			return TimeBand.EVENING_16_22;
		}
		return TimeBand.NIGHT_22_6;
	}
}

