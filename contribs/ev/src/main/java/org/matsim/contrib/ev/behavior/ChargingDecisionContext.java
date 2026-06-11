package org.matsim.contrib.ev.behavior;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import jakarta.annotation.Nullable;

/**
 * Information passed to a {@link ChargingDecisionStrategy} when probing or deciding charging
 * at a candidate activity / slot.
 * <p>
 * The record carries enough context for both the legacy {@link ChargingBehaviourModel} (which
 * only uses {@link LocationType}) and the future H/W/D/F model (which uses
 * {@link FutureChargingActivityLabel}). Each strategy implementation picks the fields it needs.
 */
public record ChargingDecisionContext(
		GroupType group,
		boolean hasHomeCharger,
		@Nullable Id<Person> personId,
		LocationType locationType,
		FutureChargingActivityLabel activityLabel,
		TimeBand timeBand,
		double soc,
		@Nullable Boolean canFinishNextTrip) {
}
