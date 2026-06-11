package org.matsim.contrib.ev.behavior;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Activity-level state for the sequential future charging behaviour model.
 */
public final class FutureChargingActivity {
	private final FutureChargingActivityLabel label;
	private final TimeBand timeBand;
	private final double socOnArrival;
	private final Boolean canFinishNextTrip;
	private final Set<FutureChargingSupplyType> feasibleSupplyTypes;

	public FutureChargingActivity(FutureChargingActivityLabel label, TimeBand timeBand, double socOnArrival,
			Boolean canFinishNextTrip, Set<FutureChargingSupplyType> feasibleSupplyTypes) {
		this.label = Objects.requireNonNull(label, "label");
		this.timeBand = Objects.requireNonNull(timeBand, "timeBand");
		this.socOnArrival = socOnArrival;
		this.canFinishNextTrip = canFinishNextTrip;
		// Defensive copy at construction time so callers cannot mutate the activity afterwards.
		// Stored as an unmodifiable view so the (very hot) getFeasibleSupplyTypes() does not need
		// to allocate a fresh copy on every probability/decision call.
		EnumSet<FutureChargingSupplyType> snapshot;
		if (feasibleSupplyTypes == null || feasibleSupplyTypes.isEmpty()) {
			snapshot = EnumSet.of(FutureChargingSupplyType.FAST);
		} else {
			snapshot = EnumSet.copyOf(feasibleSupplyTypes);
		}
		this.feasibleSupplyTypes = Collections.unmodifiableSet(snapshot);
	}

	public FutureChargingActivityLabel getLabel() {
		return label;
	}

	public TimeBand getTimeBand() {
		return timeBand;
	}

	public double getSocOnArrival() {
		return socOnArrival;
	}

	public Boolean getCanFinishNextTrip() {
		return canFinishNextTrip;
	}

	public Set<FutureChargingSupplyType> getFeasibleSupplyTypes() {
		return feasibleSupplyTypes;
	}

	public boolean isHome() {
		return label == FutureChargingActivityLabel.HOME;
	}
}
