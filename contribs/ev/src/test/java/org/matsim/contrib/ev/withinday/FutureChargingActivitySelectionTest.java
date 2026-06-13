package org.matsim.contrib.ev.withinday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.contrib.ev.behavior.ChargingDecisionStrategy;
import org.matsim.contrib.ev.behavior.FutureChargingBehaviourConfigGroup;
import org.matsim.contrib.ev.behavior.FutureChargingBehaviourModel;
import org.matsim.contrib.ev.behavior.FutureChargingBehaviourParameters;
import org.matsim.contrib.ev.behavior.FutureChargingDecisionStrategy;
import org.matsim.contrib.ev.behavior.FutureChargingSupplyType;
import org.matsim.contrib.ev.behavior.GroupType;
import org.matsim.contrib.ev.fleet.ElectricVehicle;
import org.matsim.contrib.ev.infrastructure.Charger;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructure;
import org.matsim.contrib.ev.strategic.utils.TestScenarioBuilder;
import org.matsim.contrib.ev.strategic.utils.TestScenarioBuilder.TestScenario;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.testcases.MatsimTestUtils;

import com.google.inject.Inject;

public class FutureChargingActivitySelectionTest {
	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	public void testPreferredHomeActivitySuppressesLatentNonHomeDemand() {
		TestRun run = runFutureScenario(true);

		assertEquals(0, run.model().consumeLatentPublicDemandRecords().size(),
				"Selecting HOME must not also create latent PFC demand on an earlier work activity.");
		assertEquals(1, run.scenario().tracker().startChargingProcessEvents.size());
		assertEquals(1, run.scenario().tracker().finishChargingProcessEvents.size());
	}

	@Test
	public void testPreferredHomeActivityDoesNotRequirePreplannedHomeSlot() {
		TestRun run = runFutureScenario(true, 0.8, 0.0, NoPreplannedSlotProvider.class);

		assertEquals(0, run.model().consumeLatentPublicDemandRecords().size(),
				"Selecting HOME must not fall back to latent demand when no strategic home slot was preplanned.");
		assertEquals(1, run.scenario().tracker().startChargingProcessEvents.size(),
				"A HOME activity with a private charger must be turned into an executable home charging slot.");
		assertEquals(1, run.scenario().tracker().finishChargingProcessEvents.size());
	}

	@Test
	public void testPreferredHomeActivityRequiresChargingModeLegForSyntheticSlot() {
		TestRun run = runFutureScenario(true, 0.8, 0.0, NoPreplannedSlotProvider.class, "walk");

		assertEquals(0, run.model().consumeLatentPublicDemandRecords().size(),
				"A non-car plan must not create latent EV charging demand.");
		assertEquals(0, run.scenario().tracker().startChargingProcessEvents.size(),
				"A HOME slot must not be synthesized when the plan has no EV car leg.");
		assertEquals(0, run.scenario().tracker().finishChargingProcessEvents.size());
	}

	@Test
	public void testPreferredHomeActivityRequiresChargingModeArrivalForSyntheticSlot() {
		TestRun run = runFutureScenario(true, 0.8, 0.0, NoPreplannedSlotProvider.class, "car", "walk", "walk");

		assertEquals(0, run.scenario().tracker().startChargingProcessEvents.size(),
				"A HOME slot must not be synthesized for a HOME activity reached by a non-EV mode.");
		assertEquals(0, run.scenario().tracker().finishChargingProcessEvents.size());
	}

	@Test
	public void testNoHomeChargerPreferredHomeCreatesHomeAdjacentFastDemand() {
		TestScenario scenario = new TestScenarioBuilder(utils)
				.setElectricVehicleRange(100_000.0)
				.addPerson("person", 0.8)
				.addActivity("home", 0, 0, 8.0 * 3600.0)
				.addActivity("work", 3, 3, 8.1 * 3600.0)
				.addActivity("home", 0, 0, 20.0 * 3600.0)
				.addActivity("work", 3, 3)
				.build();

		var person = scenario.scenario().getPopulation().getPersons().get(Id.createPersonId("person"));
		person.getAttributes().putAttribute(WithinDayEvEngine.DWELLING_TYPE_ATTRIBUTE, "apartment");
		person.getAttributes().putAttribute(WithinDayEvEngine.HOME_CHARGER_ATTRIBUTE, false);

		FutureChargingBehaviourConfigGroup cfg = new FutureChargingBehaviourConfigGroup();
		cfg.setLatentPublicDemand(true);
		cfg.setUnrestrictedLatentPublicDemand(true);
		cfg.setMinimumSoc(0.0);
		cfg.setMinimumLatentFastChargingDuration(1800.0);
		cfg.setMaximumLatentPublicFastSoc(1.0);

		FutureChargingBehaviourParameters parameters = FutureChargingBehaviourParameters.createDefault();
		var group = parameters.getGroupParameters(GroupType.APARTMENT);
		group.setRealFrequency(0.001);
		group.setLambda(0.0);

		FutureChargingBehaviourModel model = new FutureChargingBehaviourModel(cfg, parameters);
		var controler = scenario.controller();
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(FutureChargingBehaviourModel.class).toInstance(model);
			}
		});
		controler.addOverridingQSimModule(new AbstractQSimModule() {
			@Override
			protected void configureQSim() {
				bind(ChargingSlotProvider.class).to(NoPreplannedSlotProvider.class);
				bind(ChargingDecisionStrategy.class).toInstance(new FutureChargingDecisionStrategy(model));
			}
		});

		controler.run();

		assertEquals(0, scenario.tracker().startChargingProcessEvents.size(),
				"Home-adjacent PFC demand is latent and must not create a private HOME charging process.");
		var records = model.consumeLatentPublicDemandRecords();
		assertEquals(1, records.size());
		var record = records.getFirst();
		assertEquals(FutureChargingSupplyType.FAST, record.supplyType());
		assertEquals("HOME", record.activityLabel().name());
		assertEquals("HOME_ADJACENT_CONDITIONAL_DEMAND", record.demandType());
		assertTrue(record.probability() < 0.01,
				"HOME without a charger must map to PFC after HOME is selected, not pass through a second draw.");
		assertFalse(record.socUpdated());
		assertTrue(record.energyDemand() > 0.0);
		assertTrue(record.demandDuration() > 0.0);
	}

	@Test
	public void testPreferredLatentNonHomeActivitySuppressesHomeCharging() {
		TestRun run = runFutureScenario(false);

		assertEquals(0, run.scenario().tracker().startChargingProcessEvents.size(),
				"Selecting latent PFC must remove the later real HOME charging slot.");
		var records = run.model().consumeLatentPublicDemandRecords();
		assertEquals(1, records.size());
		var record = records.getFirst();
		assertEquals(FutureChargingSupplyType.FAST, record.supplyType());
		assertEquals("CONDITIONAL_DEMAND", record.demandType());
		assertFalse(record.socUpdated());
		assertTrue(record.energyDemand() > 0.0,
				"Conditional latent demand must record the required charging energy.");
		assertTrue(record.demandDuration() > 0.0,
				"Conditional latent demand must record the required charging duration.");
		assertEquals(0.0, record.chargedEnergy(), 1e-9,
				"Conditional latent demand is recorded without changing simulated SOC.");
	}

	@Test
	public void testMandatoryLatentDemandRecordsAndAppliesChargedEnergy() {
		TestRun run = runFutureScenario(false, 0.1, 0.2);

		var records = run.model().consumeLatentPublicDemandRecords();
		assertEquals(1, records.size());
		var record = records.getFirst();
		assertEquals(FutureChargingSupplyType.FAST, record.supplyType());
		assertEquals("MANDATORY_FEASIBILITY", record.demandType());
		assertTrue(record.socUpdated());
		assertTrue(record.energyDemand() > 0.0,
				"Mandatory latent demand must record the required charging energy.");
		assertEquals(record.energyDemand(), record.chargedEnergy(), 1e-9,
				"Mandatory latent charging must apply the demanded energy to simulated SOC.");
		assertTrue(record.socAfterCharging() > record.soc());
	}

	private TestRun runFutureScenario(boolean preferHome) {
		return runFutureScenario(preferHome, 0.8, 0.0);
	}

	private TestRun runFutureScenario(boolean preferHome, double initialSoc, double minimumSoc) {
		return runFutureScenario(preferHome, initialSoc, minimumSoc, MiddayHomeSlotProvider.class);
	}

	private TestRun runFutureScenario(boolean preferHome, double initialSoc, double minimumSoc,
			Class<? extends ChargingSlotProvider> slotProviderClass) {
		return runFutureScenario(preferHome, initialSoc, minimumSoc, slotProviderClass, "car");
	}

	private TestRun runFutureScenario(boolean preferHome, double initialSoc, double minimumSoc,
			Class<? extends ChargingSlotProvider> slotProviderClass, String travelMode) {
		return runFutureScenario(preferHome, initialSoc, minimumSoc, slotProviderClass, travelMode, travelMode,
				travelMode);
	}

	private TestRun runFutureScenario(boolean preferHome, double initialSoc, double minimumSoc,
			Class<? extends ChargingSlotProvider> slotProviderClass, String firstWorkMode, String homeMode,
			String secondWorkMode) {
		TestScenario scenario = new TestScenarioBuilder(utils)
				.setElectricVehicleRange(100_000.0)
				.addHomeCharger("person", 0, 0, 1, 1.0, "default")
				.addPerson("person", initialSoc)
				.addActivity("home", 0, 0, 8.0 * 3600.0)
				.addActivity("work", 3, 3, 17.0 * 3600.0, firstWorkMode)
				.addActivity("home", 0, 0, 20.0 * 3600.0, homeMode)
				.addActivity("work", 3, 3, secondWorkMode)
				.build();

		var person = scenario.scenario().getPopulation().getPersons().get(Id.createPersonId("person"));
		person.getAttributes().putAttribute(WithinDayEvEngine.DWELLING_TYPE_ATTRIBUTE, "house_with_pv");
		person.getAttributes().putAttribute(WithinDayEvEngine.HOME_CHARGER_ATTRIBUTE, true);

		FutureChargingBehaviourConfigGroup cfg = new FutureChargingBehaviourConfigGroup();
		cfg.setLatentPublicDemand(true);
		cfg.setUnrestrictedLatentPublicDemand(true);
		cfg.setMinimumSoc(minimumSoc);
		cfg.setMinimumLatentFastChargingDuration(0.0);
		cfg.setMaximumLatentPublicFastSoc(1.0);

		FutureChargingBehaviourParameters parameters = FutureChargingBehaviourParameters.createDefault();
		var group = parameters.getGroupParameters(GroupType.HOUSE_WITH_PV);
		group.setRealFrequency(0.5);
		group.setLambda(0.0);
		group.setRhoHome(preferHome ? 30.0 : -30.0);
		group.setRhoAway(preferHome ? -30.0 : 30.0);

		FutureChargingBehaviourModel model = new FutureChargingBehaviourModel(cfg, parameters);
		var controler = scenario.controller();
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(FutureChargingBehaviourModel.class).toInstance(model);
			}
		});
		controler.addOverridingQSimModule(new AbstractQSimModule() {
			@Override
			protected void configureQSim() {
				bind(ChargingSlotProvider.class).to(slotProviderClass);
				bind(ChargingDecisionStrategy.class).toInstance(new FutureChargingDecisionStrategy(model));
			}
		});

		controler.run();
		return new TestRun(scenario, model);
	}

	private record TestRun(TestScenario scenario, FutureChargingBehaviourModel model) {
	}

	private static final class MiddayHomeSlotProvider implements ChargingSlotProvider {
		private final Charger homeCharger;

		@Inject
		MiddayHomeSlotProvider(ChargingInfrastructure infrastructure) {
			this.homeCharger = infrastructure.getChargers().values().stream()
					.filter(charger -> charger.getId().toString().contains("charger:person:person"))
					.findFirst()
					.orElseThrow();
		}

		@Override
		public List<ChargingSlot> findSlots(Person person, Plan plan, ElectricVehicle vehicle) {
			Activity middayHome = plan.getPlanElements().stream()
					.filter(Activity.class::isInstance)
					.map(Activity.class::cast)
					.filter(activity -> "home".equals(activity.getType()))
					.skip(1)
					.findFirst()
					.orElseThrow();
			return new ArrayList<>(List.of(new ChargingSlot(middayHome, middayHome, homeCharger)));
		}
	}

	private static final class NoPreplannedSlotProvider implements ChargingSlotProvider {
		@Override
		public List<ChargingSlot> findSlots(Person person, Plan plan, ElectricVehicle vehicle) {
			return List.of();
		}
	}
}
