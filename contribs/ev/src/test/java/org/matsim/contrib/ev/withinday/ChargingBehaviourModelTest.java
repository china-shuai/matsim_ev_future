package org.matsim.contrib.ev.withinday;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.ev.behavior.ChargingBehaviourModel;
import org.matsim.contrib.ev.behavior.ChargingDecisionStrategy;
import org.matsim.contrib.ev.behavior.Agent;
import org.matsim.contrib.ev.behavior.Activity;
import org.matsim.contrib.ev.behavior.GroupType;
import org.matsim.contrib.ev.behavior.LegacyChargingDecisionStrategy;
import org.matsim.contrib.ev.behavior.LocationType;
import org.matsim.contrib.ev.behavior.TimeBand;
import org.matsim.contrib.ev.fleet.ElectricVehicle;
import org.matsim.contrib.ev.infrastructure.Charger;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructure;
import org.matsim.contrib.ev.withinday.utils.WorkActivitySlotProvider;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.testcases.MatsimTestUtils;

import com.google.inject.Inject;

/**
 * Minimal integration test verifying that the charging behaviour model steers
 * charging decisions for different dwelling types.
 */
public class ChargingBehaviourModelTest {
	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	public void testThreeDwellingTypes() {
		var builder = new org.matsim.contrib.ev.strategic.utils.TestScenarioBuilder(utils);

		builder.addCharger("chargerHome", 0, 0, 3, 1.0);

		// Apartment
		builder.addPerson("apt", 0.5)
				.addActivity("home", 0, 0, 8.0 * 3600.0)
				.addActivity("work", 3, 3, 17.0 * 3600.0)
				.addActivity("home", 0, 0);

		// House with PV
		builder.addPerson("pv", 0.5)
				.addActivity("home", 0, 0, 8.0 * 3600.0)
				.addActivity("work", 3, 3, 17.0 * 3600.0)
				.addActivity("home", 0, 0);

		// House without PV
		builder.addPerson("nopv", 0.5)
				.addActivity("home", 0, 0, 8.0 * 3600.0)
				.addActivity("work", 3, 3, 17.0 * 3600.0)
				.addActivity("home", 0, 0);

		var scenario = builder.build();

		// annotate dwelling types and home charger ownership
		var population = scenario.scenario().getPopulation().getPersons();
		population.get(Id.createPersonId("apt")).getAttributes()
				.putAttribute(WithinDayEvEngine.DWELLING_TYPE_ATTRIBUTE, "apartment");
		population.get(Id.createPersonId("pv")).getAttributes()
				.putAttribute(WithinDayEvEngine.DWELLING_TYPE_ATTRIBUTE, "house_with_pv");
		population.get(Id.createPersonId("nopv")).getAttributes()
				.putAttribute(WithinDayEvEngine.DWELLING_TYPE_ATTRIBUTE, "house_no_pv");

		for (String id : List.of("apt", "pv", "nopv")) {
			population.get(Id.createPersonId(id)).getAttributes()
					.putAttribute(WithinDayEvEngine.HOME_CHARGER_ATTRIBUTE, true);
		}

		var controler = scenario.controller();

		var behaviourModel = new ChargingBehaviourModel();
		for (GroupType group : GroupType.values()) {
			behaviourModel.setSocSensitivity(group, 10.0);
		}

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(ChargingBehaviourModel.class).toInstance(behaviourModel);
			}
		});

		// ensure charging slot at work activity
		controler.addOverridingQSimModule(new AbstractQSimModule() {
			@Override
			protected void configureQSim() {
				bind(ChargingSlotProvider.class).to(WorkActivitySlotProvider.class);
				bind(ChargingDecisionStrategy.class).to(LegacyChargingDecisionStrategy.class);
			}
		});

		// initialise battery SoC at 50%
		controler.run();

		var tracker = scenario.tracker();
		assertEquals(3, tracker.startChargingProcessEvents.size(), "All agents should initiate charging at work.");
		assertEquals(3, tracker.finishChargingProcessEvents.size(), "All agents should complete charging at work.");

		for (GroupType group : GroupType.values()) {
			assertEquals(1.0, behaviourModel.getSimulatedChargingRate(group), 1e-9,
					"Expected simulated charging rate of 1.0 for group " + group);
		}

		var testAgent = new Agent(GroupType.APARTMENT, true, Id.createPersonId("test"));
		var testActivity = new Activity(LocationType.DESTINATION, TimeBand.MORNING_6_10, 0.5);
		org.junit.jupiter.api.Assertions.assertTrue(
				behaviourModel.computeChargingProbability(testAgent, testActivity) > 0.9,
				"Probability should exceed 0.9 for high sensitivity settings.");
	}

	@Test
	public void testLegacyStrategyStillLimitsChargingToOncePerDayWithoutFutureModel() {
		var builder = new org.matsim.contrib.ev.strategic.utils.TestScenarioBuilder(utils);

		builder.setElectricVehicleRange(1_000_000.0);
		builder.addCharger("charger", 3, 3, 1, 1.0);
		builder.addPerson("person", 0.1)
				.addActivity("home", 0, 0, 8.0 * 3600.0)
				.addActivity("work", 3, 3, 9.0 * 3600.0)
				.addActivity("home", 0, 0, 10.0 * 3600.0)
				.addActivity("work", 3, 3, 11.0 * 3600.0)
				.addActivity("home", 0, 0);

		var scenario = builder.build();
		var person = scenario.scenario().getPopulation().getPersons().get(Id.createPersonId("person"));
		person.getAttributes().putAttribute(WithinDayEvEngine.DWELLING_TYPE_ATTRIBUTE, "apartment");
		person.getAttributes().putAttribute(WithinDayEvEngine.HOME_CHARGER_ATTRIBUTE, false);

		var behaviourModel = new ChargingBehaviourModel();
		var controler = scenario.controller();
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(ChargingBehaviourModel.class).toInstance(behaviourModel);
			}
		});
		controler.addOverridingQSimModule(new AbstractQSimModule() {
			@Override
			protected void configureQSim() {
				bind(ChargingSlotProvider.class).to(TwoWorkActivitySlotProvider.class);
				bind(ChargingDecisionStrategy.class).to(LegacyChargingDecisionStrategy.class);
			}
		});

		controler.run();

		var tracker = scenario.tracker();
		assertEquals(1, tracker.startChargingProcessEvents.size(),
				"The legacy strategy must keep its one-charge-per-day behaviour with multiple eligible slots.");
		assertEquals(1, tracker.finishChargingProcessEvents.size());
	}

	private static final class TwoWorkActivitySlotProvider implements ChargingSlotProvider {
		private final Charger charger;

		@Inject
		TwoWorkActivitySlotProvider(ChargingInfrastructure infrastructure) {
			this.charger = infrastructure.getChargers().values().stream().findFirst().orElseThrow();
		}

		@Override
		public List<ChargingSlot> findSlots(org.matsim.api.core.v01.population.Person person,
				org.matsim.api.core.v01.population.Plan plan, ElectricVehicle vehicle) {
			List<ChargingSlot> slots = new java.util.ArrayList<>();
			for (var element : plan.getPlanElements()) {
				if (element instanceof org.matsim.api.core.v01.population.Activity activity
						&& "work".equals(activity.getType())) {
					slots.add(new ChargingSlot(activity, activity, charger));
				}
			}
			return slots;
		}
	}
}
