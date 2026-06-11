package org.matsim.contrib.ev.withinday.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.ev.fleet.ElectricVehicle;
import org.matsim.contrib.ev.infrastructure.Charger;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructure;
import org.matsim.contrib.ev.withinday.ChargingSlot;
import org.matsim.contrib.ev.withinday.ChargingSlotProvider;
import org.matsim.contrib.ev.withinday.WithinDayEvConfigGroup;
import org.matsim.contrib.ev.withinday.WithinDayEvEngine;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Activity-based slot provider that generates charging opportunities at every
 * non-stage activity where the car is available, regardless of the activity type
 * (e.g. home, work, leisure, shopping).
 *
 * <p>
 * This is primarily intended to ensure that the {@link org.matsim.contrib.ev.behavior.ChargingBehaviourModel}
 * is invoked during within-day simulations without requiring a strategic
 * charging plan.
 */
@Singleton
public class WorkActivitySlotProvider implements ChargingSlotProvider {
	private static final Logger LOG = LogManager.getLogger(WorkActivitySlotProvider.class);

	private final Scenario scenario;
	private final String carMode;
	private final Map<Id<Link>, List<Charger>> chargersByLink = new HashMap<>();

	@Inject
	public WorkActivitySlotProvider(ChargingInfrastructure infrastructure, Scenario scenario) {
		this.scenario = scenario;
		this.carMode = WithinDayEvConfigGroup.get(scenario.getConfig(), true).getCarMode();

		for (Charger charger : infrastructure.getChargers().values()) {
			chargersByLink.computeIfAbsent(charger.getLink().getId(), id -> new ArrayList<>()).add(charger);
		}
	}

	@Override
	public List<ChargingSlot> findSlots(Person person, Plan plan, ElectricVehicle vehicle) {
		List<ChargingSlot> slots = new ArrayList<>();
		List<PlanElement> elements = plan.getPlanElements();

		for (int i = 0; i < elements.size(); i++) {
			PlanElement element = elements.get(i);

			if (element instanceof Activity activity) {
				if (TripStructureUtils.isStageActivityType(activity.getType())
						&& !WithinDayEvEngine.isManagedActivityType(activity.getType())) {
					continue;
				}

				if (!isCarAvailableAtActivity(elements, i)) {
					continue;
				}

				Charger charger = findChargerAtActivity(activity);

				if (charger == null) {
					Link link = resolveLink(activity);
					LOG.debug("No charger found on link {} for activity {} (person {}).",
							link != null ? link.getId() : null, activity.getType(), person.getId());
					continue;
				}

				slots.add(new ChargingSlot(activity, activity, charger));
			}
		}

		return slots;
	}

	private boolean isCarAvailableAtActivity(List<PlanElement> elements, int activityIndex) {
		if (activityIndex == 0) {
			return false;
		}

		for (int i = activityIndex - 1; i >= 0; i--) {
			PlanElement previous = elements.get(i);

			if (previous instanceof Leg leg) {
				return carMode.equals(leg.getMode()) || carMode.equals(leg.getRoutingMode());
			}

			if (previous instanceof Activity previousActivity) {
				if (!TripStructureUtils.isStageActivityType(previousActivity.getType())
						|| WithinDayEvEngine.isManagedActivityType(previousActivity.getType())) {
					return false;
				}
			}
		}

		return false;
	}

	private Charger findChargerAtActivity(Activity activity) {
		Link link = resolveLink(activity);
		if (link == null) {
			return null;
		}

		List<Charger> chargers = chargersByLink.get(link.getId());
		if (chargers == null || chargers.isEmpty()) {
			return null;
		}

		return chargers.get(0);
	}

	private Link resolveLink(Activity activity) {
		Id<Link> linkId = PopulationUtils.decideOnLinkIdForActivity(activity, scenario);
		if (linkId == null) {
			return null;
		}
		return scenario.getNetwork().getLinks().get(linkId);
	}
}

