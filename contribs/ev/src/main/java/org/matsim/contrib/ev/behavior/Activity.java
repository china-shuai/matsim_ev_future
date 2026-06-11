package org.matsim.contrib.ev.behavior;

import java.util.Objects;

/**
 * Activity arrival information relevant for a charging decision.
 */
public final class Activity {
	private final LocationType locationType;
	private final TimeBand timeBand;
	private final double socOnArrival;
	private final Boolean canFinishNextTrip;

	/**
	 * @param locationType
	 *            activity location type
	 * @param timeBand
	 *            time band of the activity
	 * @param socOnArrival
	 *            state of charge (0-1) upon arrival
	 * @param canFinishNextTrip
	 *            optional feasibility flag (null = unknown)
	 */
	public Activity(LocationType locationType, TimeBand timeBand, double socOnArrival, Boolean canFinishNextTrip) {
		this.locationType = Objects.requireNonNull(locationType, "locationType");
		this.timeBand = Objects.requireNonNull(timeBand, "timeBand");
		this.socOnArrival = socOnArrival;
		this.canFinishNextTrip = canFinishNextTrip;
	}

	public Activity(LocationType locationType, TimeBand timeBand, double socOnArrival) {
		this(locationType, timeBand, socOnArrival, null);
	}

	public LocationType getLocationType() {
		return locationType;
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
}

