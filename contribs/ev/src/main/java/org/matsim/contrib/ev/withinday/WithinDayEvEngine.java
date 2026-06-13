package org.matsim.contrib.ev.withinday;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.IdSet;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler;
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.ev.charging.ChargingStartEvent;
import org.matsim.contrib.ev.charging.ChargingStartEventHandler;
import org.matsim.contrib.ev.charging.ChargingStrategy;
import org.matsim.contrib.ev.charging.QueuedAtChargerEvent;
import org.matsim.contrib.ev.charging.QueuedAtChargerEventHandler;
import org.matsim.contrib.ev.behavior.Agent;
import org.matsim.contrib.ev.behavior.ChargingDecision;
import org.matsim.contrib.ev.behavior.ChargingDecisionContext;
import org.matsim.contrib.ev.behavior.ChargingDecisionStrategy;
import org.matsim.contrib.ev.behavior.FutureChargingActivityLabel;
import org.matsim.contrib.ev.behavior.FutureChargingActivity;
import org.matsim.contrib.ev.behavior.FutureChargingBehaviourModel;
import org.matsim.contrib.ev.behavior.FutureChargingSupplyType;
import org.matsim.contrib.ev.behavior.GroupType;
import org.matsim.contrib.ev.behavior.LocationType;
import org.matsim.contrib.ev.behavior.TimeBand;
import org.matsim.contrib.ev.fleet.ElectricFleet;
import org.matsim.contrib.ev.fleet.ElectricVehicle;
import org.matsim.contrib.ev.infrastructure.Charger;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructure;
import org.matsim.contrib.ev.strategic.infrastructure.PersonChargerProvider;
import org.matsim.contrib.ev.discharging.AuxEnergyConsumption;
import org.matsim.contrib.ev.discharging.DriveEnergyConsumption;
import org.matsim.contrib.ev.withinday.events.AbortChargingAttemptEvent;
import org.matsim.contrib.ev.withinday.events.AbortChargingProcessEvent;
import org.matsim.contrib.ev.withinday.events.FinishChargingAttemptEvent;
import org.matsim.contrib.ev.withinday.events.FinishChargingProcessEvent;
import org.matsim.contrib.ev.withinday.events.StartChargingAttemptEvent;
import org.matsim.contrib.ev.withinday.events.StartChargingProcessEvent;
import org.matsim.contrib.ev.withinday.events.UpdateChargingAttemptEvent;
import org.matsim.contrib.ev.withinday.stats.ChargingDecisionCollector;
import org.matsim.contrib.ev.withinday.stats.ChargingDecisionCollector.Outcome;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.MobsimScopeEventHandler;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.agents.HasModifiablePlan;
import org.matsim.core.mobsim.qsim.agents.WithinDayAgentUtils;
import org.matsim.core.mobsim.qsim.interfaces.MobsimEngine;
import org.matsim.core.mobsim.qsim.interfaces.MobsimVehicle;
import org.matsim.core.mobsim.qsim.qnetsimengine.QLinkI;
import org.matsim.core.mobsim.qsim.qnetsimengine.QVehicle;
import org.matsim.core.mobsim.qsim.qnetsimengine.QVehicleFactory;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import org.matsim.core.population.routes.NetworkRoute;

import com.google.common.base.Preconditions;

import jakarta.annotation.Nullable;

/**
 * This engine is the core of the within-day electric vehicle charging package.
 * It mainly works with two interfaces, ChargingSlotProvider and
 * ChargingAlternativeProvider. In the beginning of the day, the
 * ChargingSlotProvider is called to integate planned charging activities into
 * the schedule of each viable agent. The ChargingAlternativeProvider is called
 * throghout the day, for instance, to change the charger when an agent notices
 * that a planned charger is blocked. The engine maanges the successive
 * adaptation of the plan to the actions intended by the agent (pluggin the car,
 * unpluggin the car, ...).
 * 
 * @author Sebastian Hörl (sebhoerl), IRT SystemX
 */
public class WithinDayEvEngine implements MobsimEngine, ActivityStartEventHandler, ActivityEndEventHandler, ChargingStartEventHandler,
		QueuedAtChargerEventHandler, PersonDepartureEventHandler, MobsimScopeEventHandler {
	static public final String ACTIVE_PERSON_ATTRIBUTE = "wevc:active";
	static public final String MAXIMUM_QUEUE_TIME_PERSON_ATTRIBUTE = "wevc:maximumQueueTime";

	static public final String PLUG_ACTIVITY_TYPE = "ev:plug interaction";
	static public final String UNPLUG_ACTIVITY_TYPE = "ev:unplug interaction";
	static public final String WAIT_ACTIVITY_TYPE = "ev:wait interaction";

	// used when charging at the first acitivty fails and the person needs to
	// recover the vehicle
	static public final String ACCESS_ACTIVITY_TYPE = "ev:access interaction";
	static public final String DWELLING_TYPE_ATTRIBUTE = "ev:dwellingType";
	static public final String HOME_CHARGER_ATTRIBUTE = "ev:hasHomeCharger";
	static private final double SECONDS_PER_DAY = 24 * 3600.0;

	static public final String CHARGING_SLOT_ATTRIBUTE = "ev:chargingSlot";
	static public final String CHARGING_PROCESS_ATTRIBUTE = "ev:chargingProcess";
	static private final String INITIAL_ACTIVITY_END_TIME_ATTRIBUTE = "ev:initialActivityEndTime";

	private final String chargingMode;
	private final QSim qsim;

	private final Vehicles vehicles;
	private final QVehicleFactory qVehicleFactory;
	private final Scenario scenario;
	private final WithinDayChargingStrategy.Factory chargingStrategyFactory;

	private final ElectricFleet electricFleet;
	private final ChargingAlternativeProvider alternativeProvider;
	private final ChargingSlotProvider slotProvider;
	private final EventsManager eventsManager;
	private final TimeInterpretation timeInterpretation;
	private ChargingScheduler chargingScheduler;
	private final ChargingInfrastructure chargingInfrastructure;
	private final ChargingSlotFinder chargingSlotFinder;
	@Nullable
	private final ChargingDecisionStrategy chargingDecisionStrategy;
	@Nullable
	private final FutureChargingBehaviourModel futureChargingBehaviourModel;
	@Nullable
	private final ChargingDecisionCollector chargingDecisionCollector;

	private final boolean performAbort;
	private final double maximumQueueWaitTime;
	private final boolean allowSpontaneousCharging;

	private final Logger logger = LogManager.getLogger(WithinDayEvEngine.class);

	public WithinDayEvEngine(WithinDayEvConfigGroup config, QSim qsim, TimeInterpretation timeInterpretation,
			ElectricFleet electricFleet, ChargingAlternativeProvider onlineSlotProvider,
			ChargingSlotProvider offlineSlotProvider, EventsManager eventsManager, ChargingScheduler chargingScheduler,
			Vehicles vehicles, QVehicleFactory qVehicleFactory, Scenario scenario,
			WithinDayChargingStrategy.Factory chargingStrategyFactory, ChargingInfrastructure chargingInfrastructure,
			@Nullable ChargingDecisionStrategy chargingDecisionStrategy,
			@Nullable FutureChargingBehaviourModel futureChargingBehaviourModel,
			@Nullable ChargingDecisionCollector chargingDecisionCollector) {
		this.qsim = qsim;
		this.timeInterpretation = timeInterpretation;
		this.electricFleet = electricFleet;
		this.alternativeProvider = onlineSlotProvider;
		this.slotProvider = offlineSlotProvider;
		this.eventsManager = eventsManager;
		this.chargingScheduler = chargingScheduler;
		this.vehicles = vehicles;
		this.qVehicleFactory = qVehicleFactory;
		this.scenario = scenario;
		this.chargingStrategyFactory = chargingStrategyFactory;
		this.chargingInfrastructure = Objects.requireNonNull(chargingInfrastructure, "chargingInfrastructure");
		this.chargingMode = config.getCarMode();
		this.chargingSlotFinder = new ChargingSlotFinder(scenario, chargingMode);
		this.chargingDecisionStrategy = chargingDecisionStrategy;
		this.futureChargingBehaviourModel = futureChargingBehaviourModel;
		this.chargingDecisionCollector = chargingDecisionCollector;

		this.performAbort = config.isAbortAgents();
		this.maximumQueueWaitTime = config.getMaximumQueueTime();
		this.allowSpontaneousCharging = config.isAllowSpoantaneousCharging();
	}

	// INITIALIZATION

	private final IdSet<Person> relevantPersons = new IdSet<>(Person.class);
	private final IdSet<Vehicle> relevantVehicles = new IdSet<>(Vehicle.class);
	private final IdSet<Vehicle> chargedVehiclesToday = new IdSet<>(Vehicle.class);
	private final Map<Id<Vehicle>, ChargingSlot> preferredSlotByVehicle = new HashMap<>();
	private final Map<Id<Vehicle>, Activity> preferredLatentPublicActivityByVehicle = new HashMap<>();
	private final IdSet<Vehicle> latentPublicDemandRecordedToday = new IdSet<>(Vehicle.class);
	private final IdMap<Person, PendingLatentCharging> pendingLatentChargingByPerson = new IdMap<>(Person.class);

	// Per-iteration base seed for latent-service availability sampling. Refreshed in onPrepareSim
	// so the draw varies across iterations, while staying stable for a given (person, activity,
	// supplyType) within one iteration (so selectPreferred* and evaluate* see the same outcome).
	private long latentServiceSeedBase;

	@Override
	public void onPrepareSim() {
		logger.info("Implementing charging slots ..");
		chargedVehiclesToday.clear();
		latentPublicDemandRecordedToday.clear();
		preferredSlotByVehicle.clear();
		preferredLatentPublicActivityByVehicle.clear();
		pendingLatentChargingByPerson.clear();
		latentServiceSeedBase = MatsimRandom.getRandom().nextLong();

		int activityBasedCount = 0;
		int legBasedCount = 0;
		int overnightCount = 0;
		int wholeDayCount = 0;

		for (MobsimAgent agent : qsim.getAgents().values()) {
			if (agent instanceof HasModifiablePlan) {
				Plan plan = WithinDayAgentUtils.getModifiablePlan(agent);

				// only active agents
				// only those agents that actually have a proper vehicle
				if (isActive(plan.getPerson()) && VehicleUtils.hasVehicleId(plan.getPerson(), chargingMode)) {
					relevantPersons.add(plan.getPerson().getId());

					Id<Vehicle> vehicleId = VehicleUtils.getVehicleId(plan.getPerson(), chargingMode);
					ElectricVehicle vehicle = electricFleet.getElectricVehicles().get(vehicleId);
					relevantVehicles.add(vehicleId);

					GroupType groupType = determineGroupType(plan.getPerson());
					boolean homeCharger = hasHomeCharger(plan.getPerson());
					if (chargingDecisionStrategy != null) {
						chargingDecisionStrategy.registerGroupMember(groupType, plan.getPerson().getId());
					}
					if (chargingDecisionCollector != null) {
						chargingDecisionCollector.registerPotentialParticipant(plan.getPerson().getId(), groupType);
					}
					if (futureChargingBehaviourModel != null && futureChargingBehaviourModel.isLatentPublicDemandEnabled()) {
						futureChargingBehaviourModel.registerLatentDemandPopulationMember(groupType, plan.getPerson().getId());
					}

					Activity firstActivity = (Activity) plan.getPlanElements().get(0);
					Activity lastActivity = (Activity) plan.getPlanElements().get(plan.getPlanElements().size() - 1);
					Activity overnightActivity = findOvernightActivity(plan);

					ChargingSlot overnightSlot = null;
					ChargingSlot wholeDaySlot = null;
					boolean foundRegularSlot = false;

					List<ChargingSlot> slots = new ArrayList<>(slotProvider.findSlots(plan.getPerson(), plan, vehicle));
					Collections.sort(slots, (first, second) -> {
						int firstIndex = plan.getPlanElements()
								.indexOf(first.isLegBased() ? first.leg() : first.startActivity());

						int secondIndex = plan.getPlanElements()
								.indexOf(second.isLegBased() ? second.leg() : second.startActivity());

						return Integer.compare(firstIndex, secondIndex);
					});

					boolean unifiedFutureSelection = usesUnifiedFutureActivitySelection();
					if (unifiedFutureSelection && vehicle != null && vehicle.getBattery() != null
							&& vehicleId != null && hasChargingModeLeg(plan)) {
						selectPreferredFutureActivity(plan, slots, vehicle, vehicleId, groupType, homeCharger);
						ChargingSlot selectedHomeSlot = preferredSlotByVehicle.get(vehicleId);
						if (selectedHomeSlot != null && !slots.contains(selectedHomeSlot)) {
							slots.add(selectedHomeSlot);
						}
						slots.removeIf(slot -> slot != selectedHomeSlot);
					} else if (chargingDecisionStrategy != null && vehicle != null && vehicle.getBattery() != null
							&& vehicleId != null && !slots.isEmpty()) {
						double initialSoc = vehicle.getBattery().getCapacity() > 0
								? vehicle.getBattery().getCharge() / vehicle.getBattery().getCapacity()
								: 0.0;
						Map<ChargingSlot, Double> socEstimates = estimateSocAtSlotEntries(plan, slots, vehicle);
						selectPreferredSlot(plan, slots, vehicleId, groupType, homeCharger, initialSoc, socEstimates);
					}
					for (ChargingSlot slot : slots) {
						if (slot.startActivity() == firstActivity && slot.endActivity() == lastActivity) {
							// special case: this is a slot spanning the whole plan
							Preconditions.checkState(slot.leg() == null);
							Preconditions.checkState(!foundRegularSlot);
							Preconditions.checkState(overnightSlot == null);
							Preconditions.checkState(wholeDaySlot == null);
							wholeDaySlot = slot;
							wholeDayCount++;
						} else if (slot.startActivity() == firstActivity && slot.endActivity() == overnightActivity) {
							// special case: this slot has started on the "previous day" and the vehicle
							// only needs to be unplugged after the overnight activity. In order to simplify
							// time calculation for scheduling, we treat this slot last
							Preconditions.checkState(slot.leg() == null);
							Preconditions.checkState(overnightSlot == null);
							Preconditions.checkState(wholeDaySlot == null);
							overnightSlot = slot;
							overnightCount++;
						} else if (slot.startActivity() != null && slot.endActivity() != null) {
							// standard case: schedule a plug activity along the plan
							Preconditions.checkState(slot.leg() == null);
							Preconditions.checkState(wholeDaySlot == null);
							Activity plugActivity = chargingScheduler.scheduleInitialPlugActivity(agent,
									slot.startActivity(), slot.charger());
							plugActivity.getAttributes().putAttribute(CHARGING_SLOT_ATTRIBUTE, slot);
							activityBasedCount++;
						} else {
							// leg case: schedule a plug activity along a leg
							Preconditions.checkState(slot.startActivity() == null);
							Preconditions.checkState(slot.endActivity() == null);
							Preconditions.checkState(slot.leg() != null);

							Activity plugActivity = chargingScheduler.scheduleOnroutePlugActivity(agent, slot.leg(),
									slot.charger());
							plugActivity.getAttributes().putAttribute(CHARGING_SLOT_ATTRIBUTE, slot);
							legBasedCount++;
						}
					}

					if (overnightSlot != null) {
						if (chargingDecisionStrategy == null) {
							startOvernightCharging(agent, overnightSlot);
							updateInitialVehicleLocation(plan, vehicleId, overnightSlot);
						} else {
							overnightSlot.endActivity().getAttributes().putAttribute(CHARGING_SLOT_ATTRIBUTE, overnightSlot);
						}
					}

					if (wholeDaySlot != null) {
						if (chargingDecisionStrategy == null) {
							startWholeDayCharging(agent, wholeDaySlot);
							updateInitialVehicleLocation(plan, vehicleId, wholeDaySlot);
						} else {
							wholeDaySlot.startActivity().getAttributes().putAttribute(CHARGING_SLOT_ATTRIBUTE, wholeDaySlot);
							wholeDaySlot.endActivity().getAttributes().putAttribute(CHARGING_SLOT_ATTRIBUTE, wholeDaySlot);
						}
					}
				}
			}
		}

		logger.info(String.format("  activity: %d, leg: %d, overnight: %d, whole day: %d", activityBasedCount,
				legBasedCount, overnightCount, wholeDayCount));
	}

	private Activity findOvernightActivity(Plan plan) {
		for (Trip trip : TripStructureUtils.getTrips(plan)) {
			String mode = TripStructureUtils.getRoutingModeIdentifier().identifyMainMode(trip.getTripElements());

			if (mode.equals(chargingMode)) {
				Id<Link> originLinkId = PopulationUtils.decideOnLinkIdForActivity(trip.getOriginActivity(), scenario);
				Id<Link> destinationLinkId = PopulationUtils.decideOnLinkIdForActivity(trip.getDestinationActivity(),
						scenario);

				if (!originLinkId.equals(destinationLinkId)) {
					return trip.getOriginActivity();
				}
			}
		}

		return null;
	}

	private boolean hasChargingModeLeg(Plan plan) {
		for (PlanElement element : plan.getPlanElements()) {
			if (element instanceof Leg leg && chargingMode.equals(leg.getMode())) {
				return true;
			}
		}
		return false;
	}

	private void updateInitialVehicleLocation(Plan plan, Id<Vehicle> vehicleId, ChargingSlot slot) {
		MobsimVehicle vehicle = qsim.getVehicles().get(vehicleId);

		if (vehicle == null) {
			Vehicle vehicleData = vehicles.getVehicles().get(vehicleId);
			vehicle = qVehicleFactory.createQVehicle(vehicleData);
			qsim.addParkedVehicle(vehicle, slot.charger().getLink().getId());
		}

		Id<Link> initialLinkId = vehicle.getCurrentLink().getId();

		QLinkI originalLink = (QLinkI) qsim.getNetsimNetwork().getNetsimLink(initialLinkId);
		QVehicle qVehicle = originalLink.removeParkedVehicle(vehicleId);
		Preconditions.checkNotNull(qVehicle);

		QLinkI updatedLink = (QLinkI) qsim.getNetsimNetwork().getNetsimLink(slot.charger().getLink().getId());
		updatedLink.addParkedVehicle(qVehicle);
	}

	private void startOvernightCharging(MobsimAgent agent, ChargingSlot slot) {
        Activity endActivity = slot.endActivity();
        endActivity.getAttributes().putAttribute(CHARGING_SLOT_ATTRIBUTE, slot);

        double now = internalInterface.getMobsim().getSimTimer().getSimStartTime();
        ChargingProcess process = createChargingProcess(agent.getId(), now, slot, null, false);
        if (process == null) {
            logger.warn("Failed to start overnight charging for agent {} because the slot contains no charger.",
                    agent.getId());
            return;
        }
        process.isOvernight = true;

        // Handle end time from here

        OptionalTime endTime = timeInterpretation.decideOnActivityEndTimeAlongPlan(endActivity, WithinDayAgentUtils.getModifiablePlan(agent));
        Preconditions.checkState(endTime.isDefined());

        endActivity.getAttributes().putAttribute(INITIAL_ACTIVITY_END_TIME_ATTRIBUTE, endTime.seconds());
        endActivity.setEndTime(Double.MAX_VALUE);

        WithinDayAgentUtils.resetCaches(agent);

        /*
         * We would usually include the following line, but we must not call it here
         * because it is called automatically by the QSim at simulation startup.
         * Otherwise, the agent will appear twice in the agent queue:
         *
         * WithinDayAgentUtils.rescheduleActivityEnd(agent, qsim);
         */

        plugging.put(process.vehicle.getId(), process);
    }

	private void startWholeDayCharging(MobsimAgent agent, ChargingSlot slot) {
		double now = internalInterface.getMobsim().getSimTimer().getSimStartTime();
		ChargingProcess process = createChargingProcess(agent.getId(), now, slot, null, false);
		if (process == null) {
			logger.warn("Failed to start whole-day charging for agent {} because the slot contains no charger.",
					agent.getId());
			return;
		}
		process.isWholeDay = true;

		plugging.put(process.vehicle.getId(), process);
	}

	// EVENT COLLECTION

	private final List<PersonDepartureEvent> personDepartureEvents = new LinkedList<>();
	private final List<ActivityStartEvent> activityStartEvents = new LinkedList<>();
	private final List<ActivityEndEvent> activityEndEvents = new LinkedList<>();
	private final List<QueuedAtChargerEvent> queuedAtChargerEvents = new LinkedList<>();
	private final List<ChargingStartEvent> chargingStartEvents = new LinkedList<>();

	@Override
	public void handleEvent(PersonDepartureEvent event) {
		if (event.getLegMode().equals(chargingMode) && relevantPersons.contains(event.getPersonId())) {
			synchronized (personDepartureEvents) {
				personDepartureEvents.add(event);
			}
		}
	}

	@Override
	public void handleEvent(ActivityStartEvent event) {
		if (event.getActType().equals(PLUG_ACTIVITY_TYPE) || event.getActType().equals(UNPLUG_ACTIVITY_TYPE)
				|| (futureChargingBehaviourModel != null && futureChargingBehaviourModel.isLatentPublicDemandEnabled()
						&& relevantPersons.contains(event.getPersonId()))) {
			synchronized (activityStartEvents) {
				activityStartEvents.add(event);
			}
		}
	}

	@Override
	public void handleEvent(ActivityEndEvent event) {
		if (futureChargingBehaviourModel != null && futureChargingBehaviourModel.isLatentPublicDemandEnabled()
				&& relevantPersons.contains(event.getPersonId())) {
			synchronized (activityEndEvents) {
				activityEndEvents.add(event);
			}
		}
	}

	@Override
	public void handleEvent(QueuedAtChargerEvent event) {
		if (relevantVehicles.contains(event.getVehicleId())) {
			synchronized (queuedAtChargerEvents) {
				queuedAtChargerEvents.add(event);
			}
		}
	}

	@Override
	public void handleEvent(ChargingStartEvent event) {
		if (relevantVehicles.contains(event.getVehicleId())) {
			synchronized (chargingStartEvents) {
				chargingStartEvents.add(event);
			}
		}
	}

	// MANAGING CHARGING PROCESSES

	private class ChargingProcess {
		MobsimAgent agent;
		ElectricVehicle vehicle;

		// search process
		boolean isFirstAttempt = true;
		int attemptIndex = 0;
		int processIndex;

		// plugging process
		double latestPlugTime;

		// charging slots
		ChargingSlot initialSlot;
		ChargingSlot currentSlot;
		List<ChargingAlternative> trace = new LinkedList<>();

		// state variables trigger by events
		boolean isSubmitted = false;
		boolean isQueued = false;
		boolean isPlugged = false;

			// markers for special cases
			boolean isOvernight = false;
			boolean isWholeDay = false;
			double activityChargingEndTime = Double.NaN;
			boolean chargingEndedBeforePlannedUnplug = false;
		}

	private ChargingProcess createChargingProcessFromPlugActivity(Id<Person> personId, double now) {
		MobsimAgent agent = qsim.getAgents().get(personId);
		if (agent == null) {
			return null;
		}

		Plan plan = WithinDayAgentUtils.getModifiablePlan(agent);
		int plugActivityIndex = WithinDayAgentUtils.getCurrentPlanElementIndex(agent);
		var planElement = plan.getPlanElements().get(plugActivityIndex);
		if (!(planElement instanceof Activity plugActivity)) {
			logger.debug(
					"Activity start for person {} at time {} references plan element index {} of class {}, not an activity – skipping charging process.",
					personId, now, plugActivityIndex, planElement.getClass().getSimpleName());
			return null;
		}
		if (!PLUG_ACTIVITY_TYPE.equals(plugActivity.getType())) {
			// 普通活动，直接放行
			return null;
		}

		if (!(plugActivity.getAttributes().getAttribute(CHARGING_SLOT_ATTRIBUTE) instanceof ChargingSlot)) {
			logger.debug(
					"Plug activity for person {} at time {} (index {}) lacks charging slot metadata – skipping charging process.",
					personId, now, plugActivityIndex);
			return null;
		}

		return createChargingProcessFromPlugActivity(personId, now, plugActivity, agent, plan, false);
	}

	private ChargingProcess createChargingProcessFromLeg(Id<Person> personId, double now) {
		MobsimAgent agent = qsim.getAgents().get(personId);
		Plan plan = WithinDayAgentUtils.getModifiablePlan(agent);

		int legIndex = WithinDayAgentUtils.getCurrentPlanElementIndex(agent);
		Leg leg = (Leg) plan.getPlanElements().get(legIndex);
		Preconditions.checkState(leg.getMode().equals(chargingMode));

		Activity plugActivity = findFollowingPlugActivity(agent, plan);
		if (plugActivity == null) {
			// can only happening when creating charging process from a leg
			return null;
		}

		return createChargingProcessFromPlugActivity(personId, now, plugActivity, agent, plan, false);
	}

	private ChargingProcess createChargingProcessFromPlugActivity(Id<Person> personId, double now,
			Activity plugActivity, MobsimAgent agent, Plan plan, boolean isSpontaneous) {
		if (!PLUG_ACTIVITY_TYPE.equals(plugActivity.getType())) {
			logger.warn(
					"Attempted to create charging process for person {} at time {}, but current plan element is '{}' instead of '{}'. Skipping.",
					personId, now, plugActivity.getType(), PLUG_ACTIVITY_TYPE);
			return null;
		}

		ChargingProcess chargingProcess = (ChargingProcess) plugActivity.getAttributes()
				.getAttribute(CHARGING_PROCESS_ATTRIBUTE);
		if (chargingProcess != null) {
			// we are continuing an ongoing search process
			chargingProcess.isFirstAttempt = false;
			return chargingProcess;
		}

		ChargingSlot slot = (ChargingSlot) plugActivity.getAttributes().getAttribute(CHARGING_SLOT_ATTRIBUTE);
		DecisionOutcome decisionOutcome = evaluateChargingDecision(personId, agent, plan, slot, plugActivity, now);
		if (decisionOutcome == null) {
			return null;
		}

		if (decisionOutcome.defer()) {
			return null;
		}

		ChargingSlot decidedSlot = decisionOutcome.slot();
		Activity effectivePlugActivity = decisionOutcome.plugActivity();

		effectivePlugActivity.getAttributes().putAttribute(CHARGING_SLOT_ATTRIBUTE, decidedSlot);
		return createChargingProcess(personId, now, decidedSlot, effectivePlugActivity, isSpontaneous);
	}

	private final IdMap<Person, Integer> chargingProcessIndex = new IdMap<>(Person.class);

	private ChargingProcess createChargingProcess(Id<Person> personId, double now, ChargingSlot slot,
			Activity plugActivity,
			boolean isSpontaneous) {
		if (slot == null || slot.charger() == null) {
			logger.warn(
					"Cannot create charging process for person {} at time {} because charging slot or charger is undefined.",
					personId, now);
			if (plugActivity != null) {
				plugActivity.getAttributes().removeAttribute(CHARGING_PROCESS_ATTRIBUTE);
			}
			return null;
		}

		MobsimAgent agent = qsim.getAgents().get(personId);
		Plan plan = WithinDayAgentUtils.getModifiablePlan(agent);

		Id<Vehicle> vehicleId = VehicleUtils.getVehicleId(plan.getPerson(), chargingMode);
		ElectricVehicle vehicle = electricFleet.getElectricVehicles().get(vehicleId);

		ChargingProcess process = new ChargingProcess();
		process.processIndex = chargingProcessIndex.compute(personId, (id, value) -> {
			return value == null ? 0 : value + 1;
		});
		process.agent = agent;
		process.vehicle = vehicle;
		process.currentSlot = slot;
		process.initialSlot = slot;

		eventsManager.processEvent(new StartChargingProcessEvent(now, process.agent.getId(), process.vehicle.getId(),
				process.processIndex));
		eventsManager.processEvent(
				new StartChargingAttemptEvent(now, personId, vehicleId, process.currentSlot.charger().getId(),
						process.attemptIndex, process.processIndex, process.currentSlot.isLegBased(), isSpontaneous,
						process.currentSlot.duration()));

		if (plugActivity != null) {
			plugActivity.getAttributes().putAttribute(CHARGING_PROCESS_ATTRIBUTE, process);
		}

		return process;
	}

	private Activity findFollowingPlugActivity(MobsimAgent agent, Plan plan) {
		int currentIndex = WithinDayAgentUtils.getCurrentPlanElementIndex(agent);
		PlanElement currentElement = plan.getPlanElements().get(currentIndex);
		Preconditions.checkState(currentElement instanceof Leg);

		for (int k = currentIndex + 1; k < plan.getPlanElements().size(); k++) {
			PlanElement element = plan.getPlanElements().get(k);

			if (element instanceof Activity activity) {
				if (!TripStructureUtils.isStageActivityType(activity.getType())
						|| isManagedActivityType(activity.getType())) {
					if (activity.getType().equals(PLUG_ACTIVITY_TYPE)) {
						return activity;
					} else {
						return null; // there is no plug activity between here and next main activity
					}
				}
			}
		}

		return null;
	}

	private record DecisionOutcome(ChargingSlot slot, Activity plugActivity, boolean defer) {
	}

	private record AdaptationResult(ChargingSlot slot, Activity plugActivity, boolean proceed, boolean replanned) {
	}

	private DecisionOutcome evaluateChargingDecision(Id<Person> personId, MobsimAgent agent, Plan plan,
			ChargingSlot slot,
			Activity plugActivity, double now) {
		if (chargingDecisionStrategy == null || slot == null) {
			return new DecisionOutcome(slot, plugActivity, false);
		}
		if (agent == null || plan == null) {
			return new DecisionOutcome(slot, plugActivity, false);
		}

		GroupType groupType = determineGroupType(plan.getPerson());
		boolean homeCharger = hasHomeCharger(plan.getPerson());
		LocationType locationType = determineLocationType(slot);
		FutureChargingActivityLabel activityLabel = determineFutureActivityLabel(slot);

		Id<Vehicle> vehicleId = VehicleUtils.getVehicleId(plan.getPerson(), chargingMode);
		if (vehicleId != null && chargedVehiclesToday.contains(vehicleId)) {
			logger.debug("Vehicle {} already charged earlier today. Skipping new charging attempt.", vehicleId);
			recordChargingDecision(personId, vehicleId, groupType, homeCharger,
					plugActivity != null ? plugActivity.getLinkId()
							: slot != null && slot.charger() != null ? slot.charger().getLink().getId() : null,
					now, locationType, "alreadyCharged", Double.NaN, false, Outcome.ALREADY_CHARGED_TODAY,
					"vehicle already charged today");
			skipChargingAtPlug(agent, plugActivity, now);
			return null;
		}

		ElectricVehicle vehicle = findElectricVehicle(plan);
		if (vehicle == null || vehicle.getBattery() == null) {
			recordChargingDecision(personId, vehicleId, groupType, homeCharger,
					plugActivity != null ? plugActivity.getLinkId() : null, now,
					locationType, "unknown", Double.NaN, false, Outcome.NO_EV_REGISTERED,
					"no electric vehicle registered for person");
			return new DecisionOutcome(slot, plugActivity, false);
		}

		double capacity = vehicle.getBattery().getCapacity();
		if (capacity <= 0.0) {
			return new DecisionOutcome(slot, plugActivity, false);
		}

		double soc = vehicle.getBattery().getCharge() / capacity;
		soc = Math.max(0.0, Math.min(1.0, soc));

		TimeBand timeBand = determineTimeBand(now);
		ChargingDecisionContext ctx = new ChargingDecisionContext(groupType, homeCharger, plan.getPerson().getId(),
				locationType, activityLabel, timeBand, soc, null);

		if (vehicleId != null) {
			ChargingSlot preferredSlot = preferredSlotByVehicle.get(vehicleId);
			if (preferredSlot != null && !preferredSlot.equals(slot)
					&& !chargingDecisionStrategy.isMandatoryCharging(ctx)) {
				logger.debug("Vehicle {} skipping charging slot because another slot has higher priority.", vehicleId);
				skipChargingAtPlug(agent, plugActivity, now);
				return null;
			}
		}

		ChargingDecision decision = chargingDecisionStrategy.makeChargingDecision(ctx, MatsimRandom.getLocalInstance());

		if (!decision.willCharge()) {
			skipChargingAtPlug(agent, plugActivity, now);
			logger.debug("Agent {} skips charging at location {} (probability={}).", personId, locationType,
					decision.getProbability());
			recordChargingDecision(personId, vehicleId, groupType, homeCharger,
					plugActivity != null ? plugActivity.getLinkId()
							: slot != null && slot.charger() != null ? slot.charger().getLink().getId() : null,
					now, locationType, decision.getChargerType(), decision.getProbability(), false,
					Outcome.MODEL_DECLINED, null);
			return null;
		}

		ChargingSlot effectiveSlot = slot;
		Activity effectivePlugActivity = plugActivity;

		if (!slot.isLegBased()) {
			AdaptationResult adaptation = adaptSlotToPreference(agent, personId, vehicleId, slot, decision, locationType,
					plugActivity, now, groupType, homeCharger);
			if (adaptation != null) {
				effectiveSlot = adaptation.slot();
				effectivePlugActivity = adaptation.plugActivity();
				if (adaptation.replanned()) {
					return new DecisionOutcome(null, effectivePlugActivity, true);
				}
				if (!adaptation.proceed()) {
					skipChargingAtPlug(agent, effectivePlugActivity, now);
					return null;
				}
			}
		}

		Charger effectiveCharger = effectiveSlot != null ? effectiveSlot.charger() : null;
		if (effectiveCharger == null) {
			logger.warn(
					"Skipping charging for person {} at time {} because the selected charging slot lacks a charger assignment. Requested type={}.",
					personId, now, decision.getChargerType());
			recordChargingDecision(personId, vehicleId, groupType, homeCharger,
					effectivePlugActivity != null ? effectivePlugActivity.getLinkId() : null, now, locationType,
					decision.getChargerType(), decision.getProbability(), true, Outcome.NO_CHARGER_ON_LINK,
					"charging slot lost charger assignment");
			recordBehaviourOutcome(groupType, plan.getPerson().getId(), false, decision.isMandatory());
			if (effectivePlugActivity != null) {
				skipChargingAtPlug(agent, effectivePlugActivity, now);
			}
			return null;
		}

		Id<Link> linkId = effectivePlugActivity != null ? effectivePlugActivity.getLinkId()
				: effectiveCharger.getLink().getId();
		recordChargingDecision(personId, vehicleId, groupType, homeCharger, linkId, now, locationType,
				decision.getChargerType(), decision.getProbability(), true, Outcome.SUCCESS, null);
		recordBehaviourOutcome(groupType, plan.getPerson().getId(), true, decision.isMandatory());
		if (vehicleId != null) {
			chargedVehiclesToday.add(vehicleId);
			preferredSlotByVehicle.remove(vehicleId);
		}

		return new DecisionOutcome(effectiveSlot, effectivePlugActivity, false);
	}

	private AdaptationResult adaptSlotToPreference(MobsimAgent agent, Id<Person> personId, Id<Vehicle> vehicleId,
			ChargingSlot slot,
			ChargingDecision decision, LocationType locationType, Activity plugActivity, double now, GroupType groupType,
			boolean hasHomeCharger) {
		if (chargingInfrastructure == null || chargingDecisionStrategy == null) {
			return new AdaptationResult(slot, plugActivity, true, false);
		}

		if (slot == null || plugActivity == null) {
			return new AdaptationResult(slot, plugActivity, true, false);
		}

		String chargerType = decision.getChargerType();
		if (chargerType == null) {
			return new AdaptationResult(slot, plugActivity, true, false);
		}

		Charger current = slot.charger();
		if (current == null) {
			return new AdaptationResult(slot, plugActivity, true, false);
		}

		if (locationType == LocationType.HOME) {
			if (!hasHomeCharger) {
				// 没有家充授权，改为寻找公共快充
				double targetPower = getDcfcReferencePower();
				if (matchesPreference(current, targetPower, true)) {
					return new AdaptationResult(slot, plugActivity, true, false);
				}

				Charger preferredFast = selectPreferredCharger(slot, current, targetPower, true);
				if (preferredFast == null) {
					recordChargingDecision(personId, vehicleId, groupType, hasHomeCharger,
							plugActivity != null ? plugActivity.getLinkId()
									: (current != null ? current.getLink().getId() : null),
							now, locationType, "DCFC", decision.getProbability(), true,
							Outcome.NO_MATCHING_CHARGER_TYPE,
							"no suitable public fast charger available for non-home scenario");
					recordBehaviourOutcome(groupType, personId, false, decision.isMandatory());
					return new AdaptationResult(slot, plugActivity, false, false);
				}

				if (!preferredFast.equals(current)) {
					String logMessage = String.format(
							"Replanning home charging for person %s without private charger from %s to fast charger %s.",
							personId, current != null ? current.getId() : "unknown", preferredFast.getId());
					return replanChargingAttempt(agent, plugActivity, slot, preferredFast, vehicleId, now, logMessage);
				}

				return new AdaptationResult(slot, plugActivity, true, false);
			}

			if ("home".equalsIgnoreCase(chargerType)) {
				if (isChargerOwnedByPerson(current, personId)) {
					return new AdaptationResult(slot, plugActivity, true, false);
				}

				Charger preferredHome = findHomeChargerForPerson(personId, slot);
				if (preferredHome == null) {
					recordChargingDecision(personId, vehicleId, groupType, hasHomeCharger,
							plugActivity != null ? plugActivity.getLinkId() : current.getLink().getId(), now,
							locationType,
							chargerType, decision.getProbability(), true, Outcome.NO_MATCHING_CHARGER_TYPE,
							"no private home charger found for person");
					recordBehaviourOutcome(groupType, personId, false, decision.isMandatory());
					return new AdaptationResult(slot, plugActivity, false, false);
				}

				if (preferredHome.equals(current)) {
					return new AdaptationResult(slot, plugActivity, true, false);
				}

				String logMessage = String.format(
						"Replanning home charging for person %s from charger %s to private charger %s.",
						personId, current.getId(), preferredHome.getId());
				return replanChargingAttempt(agent, plugActivity, slot, preferredHome, vehicleId, now, logMessage);
			}

			return new AdaptationResult(slot, plugActivity, true, false);
		}

		if (locationType != LocationType.DESTINATION) {
			return new AdaptationResult(slot, plugActivity, true, false);
		}

		boolean preferHigherPower = "DCFC".equalsIgnoreCase(chargerType);
		boolean preferLowerPower = "destAC".equalsIgnoreCase(chargerType)
				|| "destination".equalsIgnoreCase(chargerType)
				|| "workplace".equalsIgnoreCase(chargerType);

		if (!preferHigherPower && !preferLowerPower) {
			return new AdaptationResult(slot, plugActivity, true, false);
		}

		double targetPower = preferHigherPower ? getDcfcReferencePower() : getAcReferencePower();

		if (matchesPreference(current, targetPower, preferHigherPower)) {
			return new AdaptationResult(slot, plugActivity, true, false);
		}

		Charger preferred = selectPreferredCharger(slot, current, targetPower, preferHigherPower);

		if (preferred == null) {
			recordChargingDecision(personId, vehicleId, groupType, hasHomeCharger,
					plugActivity != null ? plugActivity.getLinkId() : current.getLink().getId(), now, locationType,
					chargerType, decision.getProbability(), true, Outcome.NO_MATCHING_CHARGER_TYPE,
					"no destination charger matching preference");
			return new AdaptationResult(slot, plugActivity, true, false);
		}

		if (preferred.equals(current)) {
			return new AdaptationResult(slot, plugActivity, true, false);
		}

		String logMessage = String.format(
				"Replanning charging for person %s from charger %s (%.1f W) to %s (%.1f W) based on preference %s.",
				personId, current.getId(), current.getPlugPower(), preferred.getId(), preferred.getPlugPower(),
				chargerType);
		return replanChargingAttempt(agent, plugActivity, slot, preferred, vehicleId, now, logMessage);
	}

	private AdaptationResult replanChargingAttempt(MobsimAgent agent, Activity currentPlugActivity,
			ChargingSlot originalSlot, Charger preferredCharger, Id<Vehicle> vehicleId, double now, String logMessage) {
		if (logMessage != null && logger.isDebugEnabled()) {
			logger.debug(logMessage);
		}

		currentPlugActivity.getAttributes().removeAttribute(CHARGING_SLOT_ATTRIBUTE);
		currentPlugActivity.setEndTime(now);
		WithinDayAgentUtils.resetCaches(agent);
		WithinDayAgentUtils.rescheduleActivityEnd(agent, qsim);

		Activity newPlugActivity = chargingScheduler.scheduleSubsequentPlugActivity(agent, currentPlugActivity,
				preferredCharger, now);

		ChargingSlot newSlot = originalSlot.isLegBased()
				? new ChargingSlot(originalSlot.leg(), originalSlot.duration(), preferredCharger)
				: new ChargingSlot(originalSlot.startActivity(), originalSlot.endActivity(), preferredCharger);

		if (vehicleId != null) {
			preferredSlotByVehicle.put(vehicleId, newSlot);
		}
		newPlugActivity.getAttributes().putAttribute(CHARGING_SLOT_ATTRIBUTE, newSlot);

		return new AdaptationResult(newSlot, newPlugActivity, false, true);
	}

	private Charger selectPreferredCharger(ChargingSlot slot, Charger current, double targetPower,
			boolean preferHigherPower) {
		if (chargingInfrastructure == null) {
			return null;
		}

		Coord originCoord = current != null ? current.getCoord() : null;
		if (originCoord == null && slot != null) {
			if (slot.startActivity() != null) {
				originCoord = PopulationUtils.decideOnCoordForActivity(slot.startActivity(), scenario);
			} else if (slot.endActivity() != null) {
				originCoord = PopulationUtils.decideOnCoordForActivity(slot.endActivity(), scenario);
			}
		}

		double bestDistance = Double.POSITIVE_INFINITY;
		double bestPowerDelta = Double.POSITIVE_INFINITY;
		Charger best = null;

		for (Charger candidate : chargingInfrastructure.getChargers().values()) {
			if (candidate == null || candidate.equals(current)) {
				continue;
			}
			if (!matchesPreference(candidate, targetPower, preferHigherPower)) {
				continue;
			}

			double distance = Double.POSITIVE_INFINITY;
			if (originCoord != null && candidate.getCoord() != null) {
				distance = CoordUtils.calcEuclideanDistance(originCoord, candidate.getCoord());
			}
			if (Double.isInfinite(distance)) {
				continue;
			}

			double powerDelta = Math.abs(candidate.getPlugPower() - targetPower);

			if (distance < bestDistance - 1e-3
					|| (Math.abs(distance - bestDistance) < 1e-3 && powerDelta < bestPowerDelta - 1e-3)) {
				bestDistance = distance;
				bestPowerDelta = powerDelta;
				best = candidate;
			}
		}

		return best;
	}

	private boolean matchesPreference(Charger charger, double targetPower, boolean preferHigherPower) {
		double plugPower = charger.getPlugPower();
		double tolerance = 1e-6;

		if (preferHigherPower) {
			return plugPower + tolerance >= targetPower;
		} else {
			return plugPower <= targetPower + tolerance;
		}
	}

	private boolean isChargerOwnedByPerson(Charger charger, Id<Person> personId) {
		if (charger == null || personId == null) {
			return false;
		}
		Object raw = charger.getAttributes().getAttribute(PersonChargerProvider.PERSONS_CHARGER_ATTRIBUTE);
		if (!(raw instanceof String rawString) || rawString.isEmpty()) {
			return false;
		}
		for (String token : rawString.split(",")) {
			if (personId.toString().equals(token.trim())) {
				return true;
			}
		}
		return false;
	}

	private Charger findHomeChargerForPerson(Id<Person> personId, ChargingSlot slot) {
		if (chargingInfrastructure == null || personId == null) {
			return null;
		}

		Coord originCoord = null;
		if (slot != null) {
			if (slot.startActivity() != null) {
				originCoord = PopulationUtils.decideOnCoordForActivity(slot.startActivity(), scenario);
			} else if (slot.endActivity() != null) {
				originCoord = PopulationUtils.decideOnCoordForActivity(slot.endActivity(), scenario);
			}
		}

		return findHomeChargerForPerson(personId, originCoord);
	}

	private Charger findHomeChargerForPerson(Id<Person> personId, Activity activity) {
		if (chargingInfrastructure == null || personId == null || activity == null) {
			return null;
		}
		return findHomeChargerForPerson(personId, PopulationUtils.decideOnCoordForActivity(activity, scenario));
	}

	private Charger findHomeChargerForPerson(Id<Person> personId, Coord originCoord) {
		if (chargingInfrastructure == null || personId == null) {
			return null;
		}

		double bestDistance = Double.POSITIVE_INFINITY;
		Charger best = null;

		for (Charger candidate : chargingInfrastructure.getChargers().values()) {
			if (!isChargerOwnedByPerson(candidate, personId)) {
				continue;
			}

			double distance = 0.0;
			if (originCoord != null && candidate.getCoord() != null) {
				distance = CoordUtils.calcEuclideanDistance(originCoord, candidate.getCoord());
			}

			if (best == null || distance < bestDistance - 1e-3) {
				bestDistance = distance;
				best = candidate;
			}
		}

		return best;
	}

	private void recordChargingDecision(Id<Person> personId, Id<Vehicle> vehicleId, GroupType groupType,
			boolean hasHomeCharger, Id<Link> linkId, double time, LocationType locationType,
			String requestedChargerType, double probability, boolean willCharge, Outcome outcome,
			String failureDetail) {
		if (chargingDecisionCollector != null) {
			chargingDecisionCollector.record(personId, vehicleId, linkId, time, locationType,
					requestedChargerType != null ? requestedChargerType : "unspecified",
					Double.isNaN(probability) ? Double.NaN : probability, willCharge, outcome, groupType,
					hasHomeCharger, failureDetail);
		}
	}

	private void recordBehaviourOutcome(GroupType groupType, Id<Person> personId, boolean success, boolean mandatory) {
		if (chargingDecisionStrategy != null) {
			chargingDecisionStrategy.recordChargingOutcome(groupType, personId, success, mandatory);
		}
	}

	private double getAcReferencePower() {
		return chargingDecisionStrategy != null ? chargingDecisionStrategy.getAcReferencePower() : 0.0;
	}

	private double getDcfcReferencePower() {
		return chargingDecisionStrategy != null ? chargingDecisionStrategy.getDcfcReferencePower() : 0.0;
	}

	private void selectPreferredSlot(Plan plan, List<ChargingSlot> slots, Id<Vehicle> vehicleId, GroupType groupType,
			boolean hasHomeCharger, double defaultSoc, Map<ChargingSlot, Double> socEstimates) {
		if (vehicleId == null || slots.isEmpty() || chargingDecisionStrategy == null) {
			return;
		}

		double bestProbability = Double.NEGATIVE_INFINITY;
		ChargingSlot bestSlot = null;

		for (ChargingSlot candidate : slots) {
			double time = estimateSlotTime(candidate);
			TimeBand band = determineTimeBand(time);
			LocationType location = determineLocationType(candidate);
			FutureChargingActivityLabel activityLabel = determineFutureActivityLabel(candidate);
			double estimatedSoc = defaultSoc;
			if (socEstimates != null && socEstimates.containsKey(candidate)) {
				estimatedSoc = socEstimates.get(candidate);
			}
			ChargingDecisionContext ctx = new ChargingDecisionContext(groupType, hasHomeCharger,
					plan.getPerson().getId(), location, activityLabel, band, estimatedSoc, null);
			double probability = chargingDecisionStrategy.computeChargingProbability(ctx);
			if (probability > bestProbability || (probability == bestProbability && bestSlot == null)) {
				bestProbability = probability;
				bestSlot = candidate;
			}
		}

		if (bestSlot != null) {
			preferredSlotByVehicle.put(vehicleId, bestSlot);
		}
	}

	private boolean usesUnifiedFutureActivitySelection() {
		return futureChargingBehaviourModel != null && futureChargingBehaviourModel.isLatentPublicDemandEnabled();
	}

	/**
	 * Paper 3 selection path: select one preferred activity across real HOME and
	 * latent non-home opportunities before supply type is decided. This keeps HOME
	 * and latent W/D/F mutually exclusive for an agent-day, while leaving the legacy
	 * slot-based behaviour path unchanged.
	 */
	private void selectPreferredFutureActivity(Plan plan, List<ChargingSlot> slots, ElectricVehicle vehicle,
			Id<Vehicle> vehicleId, GroupType groupType, boolean hasHomeCharger) {
		if (futureChargingBehaviourModel == null || vehicle == null || vehicle.getBattery() == null
				|| vehicleId == null) {
			return;
		}
		double capacity = vehicle.getBattery().getCapacity();
		if (capacity <= 0.0) {
			return;
		}

		Map<Activity, ChargingSlot> homeSlots = new IdentityHashMap<>();
		if (hasHomeCharger) {
			for (ChargingSlot slot : slots) {
				if (slot != null && !slot.isLegBased() && slot.startActivity() != null
						&& determineFutureActivityLabel(slot) == FutureChargingActivityLabel.HOME) {
					homeSlots.putIfAbsent(slot.startActivity(), slot);
				}
			}
		}

		double initialSoc = Math.max(0.0, Math.min(1.0, vehicle.getBattery().getCharge() / capacity));
		Map<Activity, Double> socEstimates = estimateSocAtActivities(plan, vehicle);
		Map<Activity, Double> activityDurations = estimateActivityDurations(plan);
		Set<Activity> activityBasedChargingCandidateStarts = hasHomeCharger
				? findActivityBasedChargingCandidateStarts(plan)
				: Collections.emptySet();
		Agent behaviourAgent = new Agent(groupType, hasHomeCharger, plan.getPerson().getId());

		Activity bestActivity = null;
		ChargingSlot bestHomeSlot = null;
		double bestProbability = Double.NEGATIVE_INFINITY;
		Activity firstMandatoryActivity = null;
		ChargingSlot firstMandatoryHomeSlot = null;
		boolean hasNonHomeCandidate = false;
		boolean hasEligibleNonHome = false;

		for (PlanElement element : plan.getPlanElements()) {
			if (!(element instanceof Activity activity)) {
				continue;
			}
			String type = activity.getType();
			if (type == null || isManagedActivityType(type) || TripStructureUtils.isStageActivityType(type)) {
				continue;
			}

			FutureChargingActivityLabel label = FutureChargingActivityLabel.fromActivityType(type);
			double estimatedSoc = socEstimates.getOrDefault(activity, initialSoc);
			EnumSet<FutureChargingSupplyType> feasible;
			ChargingSlot homeSlot = null;

			if (label == FutureChargingActivityLabel.HOME) {
				if (plan.getPlanElements().indexOf(activity) == 0) {
					continue;
				}
				if (hasHomeCharger) {
					homeSlot = homeSlots.get(activity);
					if (homeSlot == null) {
						if (!activityBasedChargingCandidateStarts.contains(activity)) {
							continue;
						}
						Charger charger = findHomeChargerForPerson(plan.getPerson().getId(), activity);
						if (charger == null) {
							continue;
						}
						homeSlot = new ChargingSlot(activity, activity, charger);
					}
					feasible = EnumSet.of(FutureChargingSupplyType.HOME);
				} else {
					double activityDuration = activityDurations.getOrDefault(activity, estimateActivityDuration(activity));
					feasible = createLatentFeasibleSupplyTypesForDuration(plan, activity, label, false, false,
							estimatedSoc, activityDuration);
					FutureChargingActivity probe = new FutureChargingActivity(label,
							determineTimeBand(estimateActivityTime(activity)), estimatedSoc, null, feasible);
					boolean mandatory = futureChargingBehaviourModel.isMandatoryCharging(probe);
					feasible = createLatentFeasibleSupplyTypesForDuration(plan, activity, label, false, mandatory,
							estimatedSoc, activityDuration);
					if (feasible.isEmpty()) {
						continue;
					}
				}
			} else {
				hasNonHomeCandidate = true;
				double activityDuration = activityDurations.getOrDefault(activity, estimateActivityDuration(activity));
				feasible = createLatentFeasibleSupplyTypesForDuration(plan, activity, label, hasHomeCharger, false,
						estimatedSoc, activityDuration);
				FutureChargingActivity probe = new FutureChargingActivity(label,
						determineTimeBand(estimateActivityTime(activity)), estimatedSoc, null, feasible);
				boolean mandatory = futureChargingBehaviourModel.isMandatoryCharging(probe);
				feasible = createLatentFeasibleSupplyTypesForDuration(plan, activity, label, hasHomeCharger, mandatory,
						estimatedSoc, activityDuration);
				if (feasible.isEmpty()) {
					continue;
				}
				hasEligibleNonHome = true;
			}

			FutureChargingActivity futureActivity = new FutureChargingActivity(label,
					determineTimeBand(estimateActivityTime(activity)), estimatedSoc, null, feasible);
			boolean mandatory = futureChargingBehaviourModel.isMandatoryCharging(futureActivity);
			double probability = futureChargingBehaviourModel.computeChargingProbability(behaviourAgent, futureActivity);
			if (mandatory && firstMandatoryActivity == null) {
				firstMandatoryActivity = activity;
				firstMandatoryHomeSlot = homeSlot;
			}
			if (probability > bestProbability) {
				bestProbability = probability;
				bestActivity = activity;
				bestHomeSlot = homeSlot;
			}
		}

		Object personId = plan.getPerson().getId();
		if (hasNonHomeCandidate) {
			futureChargingBehaviourModel.recordLatentDemandNonHomeCandidate(groupType, personId);
		}
		if (hasEligibleNonHome) {
			futureChargingBehaviourModel.recordLatentDemandEligibleNonHome(groupType, personId);
		}

		Activity selectedActivity = firstMandatoryActivity != null ? firstMandatoryActivity : bestActivity;
		ChargingSlot selectedHomeSlot = firstMandatoryActivity != null ? firstMandatoryHomeSlot : bestHomeSlot;
		if (selectedActivity == null) {
			return;
		}

		if (selectedHomeSlot != null) {
			preferredSlotByVehicle.put(vehicleId, selectedHomeSlot);
		} else {
			preferredLatentPublicActivityByVehicle.put(vehicleId, selectedActivity);
			futureChargingBehaviourModel.recordLatentDemandPreferredSelected(groupType, personId);
		}
	}

	private Set<Activity> findActivityBasedChargingCandidateStarts(Plan plan) {
		Set<Activity> starts = Collections.newSetFromMap(new IdentityHashMap<>());
		for (ChargingSlotFinder.ActivityBasedCandidate candidate : chargingSlotFinder.findActivityBased(plan.getPerson(),
				plan)) {
			starts.add(candidate.startActivity());
		}
		return starts;
	}

	/**
	 * Records a latent public charging demand entry for the vehicle when its preferred
	 * latent activity actually starts. In restricted mode, the entry is skipped if the
	 * vehicle has already charged today; in unrestricted mode the entry is always
	 * evaluated as long as the activity matches the preferred one.
	 */
	private void evaluateLatentPublicDemand(ActivityStartEvent event, double now) {
		if (futureChargingBehaviourModel == null
				|| !futureChargingBehaviourModel.isLatentPublicDemandEnabled()) {
			return;
		}
		Id<Person> personId = event.getPersonId();
		MobsimAgent agent = qsim.getAgents().get(personId);
		if (agent == null) {
			return;
		}
		Plan plan = WithinDayAgentUtils.getModifiablePlan(agent);
		if (plan == null) {
			return;
		}
		PlanElement currentElement = WithinDayAgentUtils.getCurrentPlanElement(agent);
		if (!(currentElement instanceof Activity currentActivity)) {
			return;
		}
		Id<Vehicle> vehicleId = VehicleUtils.getVehicleId(plan.getPerson(), chargingMode);
		if (vehicleId == null) {
			return;
		}
		if (latentPublicDemandRecordedToday.contains(vehicleId)) {
			return;
		}
		if (!futureChargingBehaviourModel.isUnrestrictedLatentPublicDemand()
				&& chargedVehiclesToday.contains(vehicleId)) {
			return;
		}
		Activity preferred = preferredLatentPublicActivityByVehicle.get(vehicleId);
		if (preferred == null || preferred != currentActivity || !matchesActivity(event, preferred)) {
			return;
		}
		ElectricVehicle vehicle = electricFleet.getElectricVehicles().get(vehicleId);
		if (vehicle == null || vehicle.getBattery() == null) {
			return;
		}
		double capacity = vehicle.getBattery().getCapacity();
		if (capacity <= 0.0) {
			return;
		}
		double soc = Math.max(0.0, Math.min(1.0, vehicle.getBattery().getCharge() / capacity));
		Person person = plan.getPerson();
		GroupType groupType = determineGroupType(person);
		boolean homeCharger = hasHomeCharger(person);
		String activityType = currentActivity.getType();
		FutureChargingActivityLabel label = FutureChargingActivityLabel.fromActivityType(activityType);
		if (label == FutureChargingActivityLabel.HOME && homeCharger) {
			return;
		}
		futureChargingBehaviourModel.recordLatentDemandPreferredReached(groupType, personId);
		TimeBand band = determineTimeBand(now);
		EnumSet<FutureChargingSupplyType> feasible = createLatentFeasibleSupplyTypes(plan, currentActivity, label,
				homeCharger, false, now, soc);
		FutureChargingActivity probeActivity = new FutureChargingActivity(label, band, soc, null, feasible);
		Agent behaviourAgent = new Agent(groupType, homeCharger, personId);
		boolean mandatory = futureChargingBehaviourModel.isMandatoryCharging(probeActivity);
		feasible = createLatentFeasibleSupplyTypes(plan, currentActivity, label, homeCharger, mandatory, now, soc);
		if (feasible.isEmpty()) {
			return;
		}
		futureChargingBehaviourModel.recordLatentDemandDecisionEvaluated(groupType, personId);
		FutureChargingActivity futureActivity = new FutureChargingActivity(label, band, soc, null, feasible);
		if (label == FutureChargingActivityLabel.HOME && !homeCharger) {
			double probability = mandatory ? 1.0
					: futureChargingBehaviourModel.computeChargingProbability(behaviourAgent, futureActivity);
			futureChargingBehaviourModel.recordLatentDemandGenerated(groupType, personId, mandatory);
			recordPendingLatentCharging(event, personId, vehicleId, groupType, label, activityType, now, band, soc,
					probability, mandatory, FutureChargingSupplyType.FAST,
					futureChargingBehaviourModel.computeOpportunityValue(groupType, futureActivity));
			return;
		}
		var decision = futureChargingBehaviourModel.makeLatentPublicDemandDecision(behaviourAgent, futureActivity,
				MatsimRandom.getLocalInstance());
		if (!decision.willCharge() || !futureChargingBehaviourModel.isLatentDemandSupplyType(decision.getSupplyType())) {
			return;
		}
		futureChargingBehaviourModel.recordLatentDemandGenerated(groupType, personId, decision.isMandatory());
		recordPendingLatentCharging(event, personId, vehicleId, groupType, label, activityType, now, band, soc,
				decision.getProbability(), decision.isMandatory(), decision.getSupplyType(),
				decision.getOpportunityValue());
	}

	private void recordPendingLatentCharging(ActivityStartEvent event, Id<Person> personId, Id<Vehicle> vehicleId,
			GroupType groupType, FutureChargingActivityLabel label, String activityType, double now, TimeBand band,
			double soc, double probability, boolean mandatory, FutureChargingSupplyType supplyType,
			double opportunityValue) {
		Id<Link> linkId = event.getLinkId();
		pendingLatentChargingByPerson.put(personId,
				new PendingLatentCharging(
						personId,
						vehicleId,
						groupType,
						label,
						activityType,
						linkId != null ? linkId.toString() : null,
						now,
						band,
						soc,
						probability,
						mandatory,
						supplyType,
						opportunityValue));
		chargedVehiclesToday.add(vehicleId);
		latentPublicDemandRecordedToday.add(vehicleId);
	}

	private void completeLatentCharging(ActivityEndEvent event) {
		PendingLatentCharging pending = pendingLatentChargingByPerson.get(event.getPersonId());
		if (pending == null || !pending.activityType().equals(event.getActType())
				|| event.getTime() < pending.startTime()) {
			return;
		}
		pendingLatentChargingByPerson.remove(event.getPersonId());

		ElectricVehicle vehicle = electricFleet.getElectricVehicles().get(pending.vehicleId());
		LatentChargingUpdate chargingUpdate = calculateLatentCharging(vehicle, event.getTime() - pending.startTime(),
				pending.supplyType(), pending.mandatory());

		futureChargingBehaviourModel.recordLatentPublicDemand(
				new FutureChargingBehaviourModel.LatentPublicDemandRecord(
						pending.personId().toString(),
						pending.vehicleId().toString(),
						pending.group(),
						pending.activityLabel(),
						pending.activityType(),
						pending.linkId(),
						pending.startTime(),
						pending.timeBand(),
						pending.soc(),
						pending.probability(),
						pending.mandatory(),
						pending.supplyType(),
						pending.opportunityValue(),
						createLatentDemandType(pending),
						chargingUpdate.socUpdated(),
						chargingUpdate.demandDuration(),
						chargingUpdate.energyDemand(),
						chargingUpdate.duration(),
						chargingUpdate.energy(),
						chargingUpdate.socAfter()));
		futureChargingBehaviourModel.recordChargingOutcome(pending.group(), pending.personId(), true, pending.mandatory());
		if (chargingUpdate.socUpdated()) {
			futureChargingBehaviourModel.recordLatentDemandSocUpdated(pending.group(), pending.personId());
		}
	}

	private String createLatentDemandType(PendingLatentCharging pending) {
		String demandType = pending.mandatory() ? "MANDATORY_FEASIBILITY" : "CONDITIONAL_DEMAND";
		if (pending.activityLabel() == FutureChargingActivityLabel.HOME
				&& pending.supplyType() == FutureChargingSupplyType.FAST) {
			return "HOME_ADJACENT_" + demandType;
		}
		return demandType;
	}

	private record PendingLatentCharging(Id<Person> personId, Id<Vehicle> vehicleId, GroupType group,
			FutureChargingActivityLabel activityLabel, String activityType, String linkId, double startTime,
			TimeBand timeBand, double soc, double probability, boolean mandatory, FutureChargingSupplyType supplyType,
			double opportunityValue) {
	}

	private record LatentChargingUpdate(double demandDuration, double energyDemand, double duration, double energy,
			double socAfter, boolean socUpdated) {
	}

	private LatentChargingUpdate calculateLatentCharging(ElectricVehicle vehicle, double duration,
			FutureChargingSupplyType supplyType, boolean updateSoc) {
		if (vehicle == null || vehicle.getBattery() == null) {
			return new LatentChargingUpdate(0.0, 0.0, 0.0, 0.0, Double.NaN, false);
		}
		double capacity = vehicle.getBattery().getCapacity();
		if (capacity <= 0.0) {
			return new LatentChargingUpdate(0.0, 0.0, 0.0, 0.0, Double.NaN, false);
		}
		double currentCharge = vehicle.getBattery().getCharge();
		double availableDuration = Math.max(0.0, duration);
		double chargingPower = Math.max(0.0, getLatentChargingPower(supplyType));
		double potentialEnergy = chargingPower * availableDuration;
		double energyDemand = Math.max(0.0, Math.min(potentialEnergy, capacity - currentCharge));
		double demandDuration = chargingPower > 0.0 ? energyDemand / chargingPower : 0.0;
		double chargedEnergy = updateSoc ? energyDemand : 0.0;
		double chargingDuration = updateSoc ? demandDuration : 0.0;
		if (updateSoc && chargedEnergy > 0.0) {
			vehicle.getBattery().setCharge(Math.min(capacity, currentCharge + chargedEnergy));
		}
		return new LatentChargingUpdate(demandDuration, energyDemand, chargingDuration, chargedEnergy,
				vehicle.getBattery().getCharge() / capacity, updateSoc && chargedEnergy > 0.0);
	}

	private double getLatentChargingPower(FutureChargingSupplyType supplyType) {
		return supplyType == FutureChargingSupplyType.FAST ? futureChargingBehaviourModel.getDcfcReferencePower()
				: futureChargingBehaviourModel.getAcReferencePower();
	}

	private boolean matchesActivity(ActivityStartEvent event, Activity preferred) {
		if (event.getActType() != null && !event.getActType().equals(preferred.getType())) {
			return false;
		}
		if (event.getLinkId() != null && preferred.getLinkId() != null
				&& !event.getLinkId().equals(preferred.getLinkId())) {
			return false;
		}
		return true;
	}

	private EnumSet<FutureChargingSupplyType> createLatentFeasibleSupplyTypes(Plan plan, Activity activity,
			FutureChargingActivityLabel label, boolean hasHomeCharger, boolean mandatory, double soc) {
		return createLatentFeasibleSupplyTypes(plan, activity, label, hasHomeCharger, mandatory, Double.NaN, soc);
	}

	private EnumSet<FutureChargingSupplyType> createLatentFeasibleSupplyTypes(Plan plan, Activity activity,
			FutureChargingActivityLabel label, boolean hasHomeCharger, boolean mandatory, double now, double soc) {
		return createLatentFeasibleSupplyTypesForDuration(plan, activity, label, hasHomeCharger, mandatory, soc,
				estimateActivityDuration(activity, now));
	}

	private EnumSet<FutureChargingSupplyType> createLatentFeasibleSupplyTypesForDuration(Plan plan, Activity activity,
			FutureChargingActivityLabel label, boolean hasHomeCharger, boolean mandatory, double soc,
			double activityDuration) {
		if (futureChargingBehaviourModel == null) {
			return EnumSet.noneOf(FutureChargingSupplyType.class);
		}
		if (label == FutureChargingActivityLabel.HOME) {
			if (hasHomeCharger) {
				return EnumSet.noneOf(FutureChargingSupplyType.class);
			}
			boolean fastLocationEligible = mandatory
					|| futureChargingBehaviourModel.hasLatentPublicFastCandidateNear(
							PopulationUtils.decideOnCoordForActivity(activity, scenario));
			boolean fastSocEligible = mandatory || !Double.isFinite(soc)
					|| soc <= futureChargingBehaviourModel.getMaximumLatentPublicFastSoc();
			boolean fastEligible = mandatory || (fastLocationEligible && fastSocEligible
					&& activityDuration >= futureChargingBehaviourModel.getMinimumLatentFastChargingDuration());
			return fastEligible ? EnumSet.of(FutureChargingSupplyType.FAST)
					: EnumSet.noneOf(FutureChargingSupplyType.class);
		}
		boolean fastLocationEligible = mandatory
				|| futureChargingBehaviourModel.hasLatentPublicFastCandidateNear(
						PopulationUtils.decideOnCoordForActivity(activity, scenario));
		boolean fastSocEligible = mandatory || !Double.isFinite(soc)
				|| soc <= futureChargingBehaviourModel.getMaximumLatentPublicFastSoc();
		boolean fastEligible = mandatory || (fastLocationEligible && fastSocEligible
				&& activityDuration >= futureChargingBehaviourModel.getMinimumLatentFastChargingDuration());
		boolean activityChargingEligible = activityDuration >= futureChargingBehaviourModel
				.getMinimumLatentActivityChargingDuration();
		EnumSet<FutureChargingSupplyType> feasible = EnumSet.noneOf(FutureChargingSupplyType.class);
		if (futureChargingBehaviourModel.isUnrestrictedLatentPublicDemand()) {
			if (fastEligible) {
				feasible.add(FutureChargingSupplyType.FAST);
			}
		} else {
			feasible = futureChargingBehaviourModel.createFeasiblePublicSupplyTypes(label, hasHomeCharger, mandatory);
			if (!fastEligible) {
				feasible.remove(FutureChargingSupplyType.FAST);
			}
		}
		if (mandatory && feasible.isEmpty()) {
			feasible.add(FutureChargingSupplyType.FAST);
		}

		if (activityChargingEligible && label == FutureChargingActivityLabel.WORK && hasLatentService(plan, activity,
				FutureChargingSupplyType.WORKPLACE, futureChargingBehaviourModel.getLatentWorkplaceServiceRate())) {
			feasible.add(FutureChargingSupplyType.WORKPLACE);
		}
		if (activityChargingEligible && label == FutureChargingActivityLabel.SHOP && hasLatentService(plan, activity,
				FutureChargingSupplyType.DESTINATION, futureChargingBehaviourModel.getLatentDestinationServiceRate())) {
			feasible.add(FutureChargingSupplyType.DESTINATION);
		}
		return feasible;
	}

	private Map<Activity, Double> estimateActivityDurations(Plan plan) {
		if (plan == null) {
			return Collections.emptyMap();
		}

		Map<Activity, Double> durations = new IdentityHashMap<>();
		double currentTime = 0.0;
		for (PlanElement element : plan.getPlanElements()) {
			if (element instanceof Activity activity) {
				double startTime = activity.getStartTime().isDefined() ? activity.getStartTime().seconds()
						: currentTime;
				double endTime = Double.NaN;
				if (activity.getEndTime().isDefined()) {
					endTime = activity.getEndTime().seconds();
				} else if (activity.getMaximumDuration().isDefined()) {
					endTime = startTime + activity.getMaximumDuration().seconds();
				}
				if (Double.isFinite(endTime)) {
					durations.put(activity, Math.max(0.0, endTime - startTime));
					currentTime = Math.max(currentTime, endTime);
				} else {
					durations.put(activity, 0.0);
					currentTime = Math.max(currentTime, startTime);
				}
			} else if (element instanceof Leg leg) {
				double departureTime = leg.getDepartureTime().isDefined() ? leg.getDepartureTime().seconds()
						: currentTime;
				currentTime = Math.max(currentTime, departureTime);
				double travelTime = estimateLegTravelTime(leg);
				if (Double.isFinite(travelTime) && travelTime > 0.0) {
					currentTime += travelTime;
				}
			}
		}
		return durations;
	}

	private double estimateActivityDuration(Activity activity) {
		return estimateActivityDuration(activity, Double.NaN);
	}

	private double estimateActivityDuration(Activity activity, double now) {
		if (activity == null) {
			return 0.0;
		}
		OptionalTime start = activity.getStartTime();
		OptionalTime end = activity.getEndTime();
		if (start.isDefined() && end.isDefined() && Double.isFinite(start.seconds())
				&& Double.isFinite(end.seconds())) {
			return Math.max(0.0, end.seconds() - start.seconds());
		}
		if (end.isDefined() && Double.isFinite(end.seconds()) && Double.isFinite(now)) {
			return Math.max(0.0, end.seconds() - now);
		}
		OptionalTime maximumDuration = activity.getMaximumDuration();
		if (maximumDuration.isDefined()) {
			return Math.max(0.0, maximumDuration.seconds());
		}
		return 0.0;
	}

	private double estimateLegTravelTime(Leg leg) {
		if (leg == null) {
			return Double.NaN;
		}
		if (leg.getTravelTime().isDefined()) {
			return leg.getTravelTime().seconds();
		}
		if (leg.getRoute() != null) {
			OptionalTime routeTravelTime = leg.getRoute().getTravelTime();
			if (routeTravelTime.isDefined()) {
				return routeTravelTime.seconds();
			}
		}
		return Double.NaN;
	}

	private boolean hasLatentService(Plan plan, Activity activity, FutureChargingSupplyType supplyType,
			double serviceRate) {
		if (serviceRate >= 1.0) {
			return true;
		}
		if (serviceRate <= 0.0 || plan == null || activity == null) {
			return false;
		}
		int activityIndex = plan.getPlanElements().indexOf(activity);
		// Build a stable per-(person, activity, supplyType) seed and combine with the
		// per-iteration base seed. Using Random.nextDouble() instead of `hash % 1_000_000`
		// gives a uniform draw in [0, 1) and avoids the modulo bias of raw int hashing,
		// while staying deterministic across the multiple lookups within one iteration.
		long stableHash = Objects.hash(plan.getPerson().getId(), activityIndex, activity.getType(),
				activity.getLinkId(), supplyType);
		double draw = new Random(latentServiceSeedBase ^ stableHash).nextDouble();
		return draw < serviceRate;
	}

	private double estimateActivityTime(Activity activity) {
		if (activity.getStartTime().isDefined()) {
			return activity.getStartTime().seconds();
		}
		if (activity.getEndTime().isDefined()) {
			return activity.getEndTime().seconds();
		}
		return 0.0;
	}

	private Map<Activity, Double> estimateSocAtActivities(Plan plan, ElectricVehicle vehicle) {
		if (vehicle == null || vehicle.getBattery() == null) {
			return Collections.emptyMap();
		}

		double capacity = vehicle.getBattery().getCapacity();
		if (capacity <= 0.0) {
			return Collections.emptyMap();
		}

		Map<Activity, Double> socByActivity = new IdentityHashMap<>();
		double charge = vehicle.getBattery().getCharge();
		double currentTime = 0.0;
		DriveEnergyConsumption driveConsumption = vehicle.getDriveEnergyConsumption();
		AuxEnergyConsumption auxConsumption = vehicle.getAuxEnergyConsumption();

		for (PlanElement element : plan.getPlanElements()) {
			if (element instanceof Activity activity) {
				socByActivity.put(activity, Math.max(0.0, Math.min(1.0, charge / capacity)));
				OptionalTime endTime = activity.getEndTime();
				if (endTime.isDefined()) {
					currentTime = endTime.seconds();
				} else {
					OptionalTime startTime = activity.getStartTime();
					if (startTime.isDefined()) {
						currentTime = Math.max(currentTime, startTime.seconds());
					}
				}
			} else if (element instanceof Leg leg) {
				double departureTime = leg.getDepartureTime().isDefined() ? leg.getDepartureTime().seconds()
						: currentTime;
				currentTime = departureTime;

				double travelTime = leg.getTravelTime().isDefined() ? leg.getTravelTime().seconds() : Double.NaN;
				if (Double.isNaN(travelTime) && leg.getRoute() != null) {
					OptionalTime routeTravelTime = leg.getRoute().getTravelTime();
					if (routeTravelTime.isDefined()) {
						travelTime = routeTravelTime.seconds();
					}
				}

				double energy = estimateLegEnergy(leg, driveConsumption, auxConsumption, departureTime, travelTime);
				charge = Math.max(0.0, charge - energy);

				if (!Double.isNaN(travelTime) && travelTime > 0.0) {
					currentTime += travelTime;
				}
			}
		}

		return socByActivity;
	}

	private Map<ChargingSlot, Double> estimateSocAtSlotEntries(Plan plan, List<ChargingSlot> slots,
			ElectricVehicle vehicle) {
		if (vehicle == null || vehicle.getBattery() == null || slots.isEmpty()) {
			return Collections.emptyMap();
		}

		double capacity = vehicle.getBattery().getCapacity();
		if (capacity <= 0.0) {
			return Collections.emptyMap();
		}

		Map<Activity, List<ChargingSlot>> activitySlots = new IdentityHashMap<>();
		Map<Leg, List<ChargingSlot>> legSlots = new IdentityHashMap<>();

		for (ChargingSlot slot : slots) {
			if (slot == null) {
				continue;
			}
			if (slot.isLegBased() && slot.leg() != null) {
				legSlots.computeIfAbsent(slot.leg(), k -> new ArrayList<>()).add(slot);
			} else if (slot.startActivity() != null) {
				activitySlots.computeIfAbsent(slot.startActivity(), k -> new ArrayList<>()).add(slot);
			}
		}

		Map<ChargingSlot, Double> socBySlot = new HashMap<>();
		double charge = vehicle.getBattery().getCharge();
		double currentTime = 0.0;
		DriveEnergyConsumption driveConsumption = vehicle.getDriveEnergyConsumption();
		AuxEnergyConsumption auxConsumption = vehicle.getAuxEnergyConsumption();

		for (PlanElement element : plan.getPlanElements()) {
			if (element instanceof Activity activity) {
				List<ChargingSlot> candidates = activitySlots.get(activity);
				if (candidates != null) {
					double soc = Math.max(0.0, Math.min(1.0, charge / capacity));
					for (ChargingSlot slot : candidates) {
						socBySlot.put(slot, soc);
					}
				}
				OptionalTime endTime = activity.getEndTime();
				if (endTime.isDefined()) {
					currentTime = endTime.seconds();
				} else {
					OptionalTime startTime = activity.getStartTime();
					if (startTime.isDefined()) {
						currentTime = Math.max(currentTime, startTime.seconds());
					}
				}
			} else if (element instanceof Leg leg) {
				List<ChargingSlot> candidates = legSlots.get(leg);
				if (candidates != null) {
					double soc = Math.max(0.0, Math.min(1.0, charge / capacity));
					for (ChargingSlot slot : candidates) {
						socBySlot.put(slot, soc);
					}
				}

				double departureTime = leg.getDepartureTime().isDefined() ? leg.getDepartureTime().seconds()
						: currentTime;
				currentTime = departureTime;

				double travelTime = leg.getTravelTime().isDefined() ? leg.getTravelTime().seconds() : Double.NaN;
				if (Double.isNaN(travelTime) && leg.getRoute() != null) {
					OptionalTime routeTravelTime = leg.getRoute().getTravelTime();
					if (routeTravelTime.isDefined()) {
						travelTime = routeTravelTime.seconds();
					}
				}

				double energy = estimateLegEnergy(leg, driveConsumption, auxConsumption, departureTime, travelTime);
				charge = Math.max(0.0, charge - energy);

				if (!Double.isNaN(travelTime) && travelTime > 0.0) {
					currentTime += travelTime;
				}
			}
		}

		return socBySlot;
	}

	private static final double MAX_AVERAGE_SPEED_FOR_CONSUMPTION = 79.9; // m/s, matches OhdeSlaski table bounds

	private double estimateLegEnergy(Leg leg, DriveEnergyConsumption driveConsumption,
			AuxEnergyConsumption auxConsumption, double departureTime, double travelTimeSeconds) {
		if (driveConsumption == null || Double.isNaN(travelTimeSeconds) || travelTimeSeconds <= 0.0) {
			return 0.0;
		}

		double totalEnergy = 0.0;
		double currentTime = departureTime;

		if (leg.getRoute() instanceof NetworkRoute networkRoute) {
			List<Id<Link>> linkSequence = new ArrayList<>();
			if (networkRoute.getStartLinkId() != null) {
				linkSequence.add(networkRoute.getStartLinkId());
			}
			linkSequence.addAll(networkRoute.getLinkIds());
			if (networkRoute.getEndLinkId() != null) {
				linkSequence.add(networkRoute.getEndLinkId());
			}

			double totalLength = 0.0;
			for (Id<Link> linkId : linkSequence) {
				Link link = scenario.getNetwork().getLinks().get(linkId);
				if (link != null) {
					totalLength += link.getLength();
				}
			}
			if (totalLength <= 0.0) {
				return 0.0;
			}

			for (Id<Link> linkId : linkSequence) {
				Link link = scenario.getNetwork().getLinks().get(linkId);
				if (link != null) {
					double segmentRatio = link.getLength() / totalLength;
					if (segmentRatio <= 0.0) {
						continue;
					}
					double segmentTime = Math.max(0.0, travelTimeSeconds * segmentRatio);
					if (link.getLength() > 0.0) {
						double minTime = link.getLength() / MAX_AVERAGE_SPEED_FOR_CONSUMPTION;
						segmentTime = Math.max(segmentTime, minTime);
					}
					double driveEnergy = driveConsumption.calcEnergyConsumption(link, segmentTime, currentTime);
					double auxEnergy = auxConsumption != null
							? auxConsumption.calcEnergyConsumption(currentTime, segmentTime, link.getId())
							: 0.0;
					totalEnergy += Math.max(0.0, driveEnergy + auxEnergy);
					currentTime += segmentTime;
				}
			}
			return Math.max(0.0, totalEnergy);
		}

		if (leg.getRoute() != null && leg.getRoute().getStartLinkId() != null) {
			Link link = scenario.getNetwork().getLinks().get(leg.getRoute().getStartLinkId());
			if (link != null) {
				if (link.getLength() > 0.0) {
					double minTime = link.getLength() / MAX_AVERAGE_SPEED_FOR_CONSUMPTION;
					travelTimeSeconds = Math.max(travelTimeSeconds, minTime);
				}
				double driveEnergy = driveConsumption.calcEnergyConsumption(link, travelTimeSeconds, departureTime);
				double auxEnergy = auxConsumption != null
						? auxConsumption.calcEnergyConsumption(departureTime, travelTimeSeconds, link.getId())
						: 0.0;
				return Math.max(0.0, driveEnergy + auxEnergy);
			}
		}

		return 0.0;
	}

	private double estimateSlotTime(ChargingSlot slot) {
		if (slot.isLegBased()) {
			Leg leg = slot.leg();
			OptionalTime departure = leg.getDepartureTime();
			if (departure.isDefined()) {
				return departure.seconds();
			}
			OptionalTime travel = leg.getTravelTime();
			if (travel.isDefined()) {
				return travel.seconds();
			}
			return 0.0;
		}
		Activity activity = slot.startActivity();
		if (activity == null) {
			activity = slot.endActivity();
		}
		if (activity == null) {
			return 0.0;
		}
		OptionalTime start = activity.getStartTime();
		if (start.isDefined()) {
			return start.seconds();
		}
		OptionalTime end = activity.getEndTime();
		return end.isDefined() ? end.seconds() : 0.0;
	}


	private void skipChargingAtPlug(MobsimAgent agent, Activity plugActivity, double now) {
		if (agent == null) {
			return;
		}
		plugActivity.setEndTime(now);
		WithinDayAgentUtils.resetCaches(agent);
		WithinDayAgentUtils.rescheduleActivityEnd(agent, qsim);
	}

	private ElectricVehicle findElectricVehicle(Plan plan) {
		try {
			Id<Vehicle> vehicleId = VehicleUtils.getVehicleId(plan.getPerson(), chargingMode);
			if (vehicleId == null) {
				return null;
			}
			return electricFleet.getElectricVehicles().get(vehicleId);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private GroupType determineGroupType(Person person) {
		Object value = person.getAttributes().getAttribute(DWELLING_TYPE_ATTRIBUTE);
		if (value == null) {
			value = PopulationUtils.getSubpopulation(person);
		}

		if (value != null) {
			String key = value.toString().toLowerCase(Locale.ROOT);
			switch (key) {
			case "apartment":
			case "multi_family":
			case "multi-family":
				return GroupType.APARTMENT;
			case "house_with_pv":
			case "housepv":
			case "pv_house":
			case "house-with-pv":
			case "house_with_solar":
			case "house-solar":
				return GroupType.HOUSE_WITH_PV;
			case "house_no_pv":
			case "house":
			case "single_family":
			case "single-family":
			case "house_without_pv":
			case "house-no-pv":
				return GroupType.HOUSE_NO_PV;
			default:
				break;
			}
		}

		return GroupType.APARTMENT;
	}

	private boolean hasHomeCharger(Person person) {
		Object value = person.getAttributes().getAttribute(HOME_CHARGER_ATTRIBUTE);
		if (value == null) {
			value = person.getAttributes().getAttribute("hasHomeCharger");
		}

		if (value instanceof Boolean booleanValue) {
			return booleanValue;
		}
		if (value instanceof String stringValue) {
			return Boolean.parseBoolean(stringValue);
		}
		return false;
	}

	private LocationType determineLocationType(ChargingSlot slot) {
		if (slot == null) {
			return LocationType.DESTINATION;
		}
		Activity primaryActivity = slot.startActivity();
		if (primaryActivity == null) {
			return LocationType.DESTINATION;
		}
		String type = primaryActivity.getType();
		if (type == null) {
			return LocationType.DESTINATION;
		}

		String normalized = type.toLowerCase(Locale.ROOT);
		if (normalized.contains("home") || normalized.contains("residence")) {
			return LocationType.HOME;
		}
		return LocationType.DESTINATION;
	}

	private FutureChargingActivityLabel determineFutureActivityLabel(ChargingSlot slot) {
		if (slot == null) {
			return FutureChargingActivityLabel.OTHER;
		}
		Activity primaryActivity = slot.startActivity();
		if (primaryActivity == null) {
			primaryActivity = slot.endActivity();
		}
		return primaryActivity == null ? FutureChargingActivityLabel.OTHER
				: FutureChargingActivityLabel.fromActivityType(primaryActivity.getType());
	}

	private TimeBand determineTimeBand(double timeSeconds) {
		double value = timeSeconds % SECONDS_PER_DAY;
		if (value < 0) {
			value += SECONDS_PER_DAY;
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

	// ENGINE LOGIC

	@Override
	public void doSimStep(double time) {
		// first process collected events
		processActivityEndEvents(time);
		processPersonDepartureEvents(time);
		processActivityStartEvents(time);
		processQueuedAtChargerEvents(time);
		processChargingStartEvents(time);

		// next advance logic
		processApproachingProcesses(time);
		processPluggingProcesses(time);
		processActivityChargingDeadlines(time);
		processUnpluggingProcesses(time);
	}

	private IdSet<Person> approaching = new IdSet<>(Person.class);
	private IdMap<Vehicle, ChargingProcess> plugging = new IdMap<>(Vehicle.class);
	private IdMap<Person, ChargingProcess> active = new IdMap<>(Person.class);
	private IdMap<Vehicle, ChargingProcess> unplugging = new IdMap<>(Vehicle.class);

	private void processPersonDepartureEvents(double now) {
		synchronized (personDepartureEvents) {
			var iterator = personDepartureEvents.iterator();

			while (iterator.hasNext()) {
				PersonDepartureEvent event = iterator.next();

				if (event.getTime() < now) {
					iterator.remove();
					approaching.add(event.getPersonId());
				}
			}
		}
	}

	private void processActivityStartEvents(double now) {
		synchronized (activityStartEvents) {
			var iterator = activityStartEvents.iterator();

			while (iterator.hasNext()) {
				ActivityStartEvent event = iterator.next();

				if (event.getTime() < now) {
					iterator.remove();

					if (event.getActType().equals(PLUG_ACTIVITY_TYPE)) {
						ChargingProcess process = createChargingProcessFromPlugActivity(event.getPersonId(), now);
						if (process != null) {
							plugging.put(process.vehicle.getId(), process);
						}
						} else if (event.getActType().equals(UNPLUG_ACTIVITY_TYPE)) {
							ChargingProcess process = active.remove(event.getPersonId());
							Preconditions.checkNotNull(process);
							unplugging.put(process.vehicle.getId(), process);
						} else if (futureChargingBehaviourModel != null
								&& futureChargingBehaviourModel.isLatentPublicDemandEnabled()) {
							evaluateLatentPublicDemand(event, now);
						}
					}
				}
			}
	}

	private void processActivityEndEvents(double now) {
		synchronized (activityEndEvents) {
			var iterator = activityEndEvents.iterator();

			while (iterator.hasNext()) {
				ActivityEndEvent event = iterator.next();

				if (event.getTime() < now) {
					iterator.remove();
					completeLatentCharging(event);
				}
			}
		}
	}

	private void processQueuedAtChargerEvents(double now) {
		synchronized (queuedAtChargerEvents) {
			var iterator = queuedAtChargerEvents.iterator();

			while (iterator.hasNext()) {
				QueuedAtChargerEvent event = iterator.next();

				if (event.getTime() < now - 1.0) { // -1.0 because event is generated in afterSimStepListener
					iterator.remove();

					ChargingProcess process = plugging.get(event.getVehicleId());

					if (process != null) {
						process.isQueued = true;
					}
				}
			}
		}
	}

	private void processChargingStartEvents(double now) {
		synchronized (chargingStartEvents) {
			var iterator = chargingStartEvents.iterator();

			while (iterator.hasNext()) {
				ChargingStartEvent event = iterator.next();

				if (event.getTime() < now - 1.0) { // -1.0 because event is generated in afterSimStepListener
					iterator.remove();

					ChargingProcess process = plugging.get(event.getVehicleId());

					if (process != null) {
						process.isPlugged = true;
					}
				}
			}
		}
	}

	private void processApproachingProcesses(double time) {
		for (Id<Person> personId : approaching) {
			MobsimAgent agent = qsim.getAgents().get(personId);
			Plan plan = WithinDayAgentUtils.getModifiablePlan(agent);
			int currentIndex = WithinDayAgentUtils.getCurrentPlanElementIndex(agent);

			if (plan.getPlanElements().get(currentIndex) instanceof Leg leg) {
				if (leg.getMode().equals(chargingMode)) {
					ChargingProcess process = createChargingProcessFromLeg(personId, time);

					if (process != null && process.isFirstAttempt) {
						// a plug activity was found and we may implement an alternative proposal

						ChargingAlternative alternative = alternativeProvider.findEnrouteAlternative(time,
								plan.getPerson(),
								plan,
								process.vehicle, process.currentSlot);

						if (alternative != null) {
							if (process.currentSlot.isLegBased() && !alternative.isLegBased()) {
								throw new IllegalStateException(
										"Cannot switch from a leg-based charging slot to an activity-based alternative because activities are not known");
							}

							if (alternative.charger() != process.currentSlot.charger()) {
								Activity followingPlugActivity = findFollowingPlugActivity(agent, plan);

								// drive to different charger and schedule a plug activity
								Activity plugActivity = chargingScheduler.changePlugActivity(process.agent,
										followingPlugActivity, alternative.charger(),
										time);
								plugActivity.getAttributes().putAttribute(CHARGING_PROCESS_ATTRIBUTE, process);

								// update slot
								process.currentSlot = new ChargingSlot(process.currentSlot.startActivity(),
										process.currentSlot.endActivity(),
										process.currentSlot.leg(), alternative.duration(),
										alternative.charger());

								// send event for scoring
								eventsManager.processEvent(new UpdateChargingAttemptEvent(time, process.agent.getId(),
										process.vehicle.getId(), alternative.charger().getId(),
										alternative.isLegBased(), alternative.duration()));
							} else if (alternative.duration() != process.currentSlot.duration()) {
								// update slot with custom duration (either switch between leg- and
								// activity-based slot, or change of duration)
								process.currentSlot = new ChargingSlot(process.currentSlot.startActivity(),
										process.currentSlot.endActivity(),
										process.currentSlot.leg(), alternative.duration(),
										process.currentSlot.charger());

								// send event for scoring
								eventsManager.processEvent(new UpdateChargingAttemptEvent(time, process.agent.getId(),
										process.vehicle.getId(), alternative.charger().getId(),
										alternative.isLegBased(), alternative.duration()));
							}
						}
					} else if (process == null && allowSpontaneousCharging) {
						// no upcoming plug activity is found, this is a completely spantaneous charging
						// attempt

						ElectricVehicle vehicle = getElectricVehicle(plan);

						ChargingAlternative alternative = alternativeProvider.findEnrouteAlternative(time,
								plan.getPerson(), plan, vehicle, null);

						if (alternative != null) {
							ChargingSlot slot = new ChargingSlot(leg, alternative.duration(),
									alternative.charger());

							Activity plugActivity = chargingScheduler.insertPlugActivity(agent,
									alternative.charger(), time);
							plugActivity.getAttributes().putAttribute(CHARGING_SLOT_ATTRIBUTE, slot);

							createChargingProcessFromPlugActivity(personId, time, plugActivity, agent, plan, true);
						}
					}
				}
			}
		}

		approaching.clear();
	}

	private ElectricVehicle getElectricVehicle(Plan plan) {
		Id<Vehicle> vehicleId = VehicleUtils.getVehicleId(plan.getPerson(), chargingMode);
		if  (!electricFleet.getElectricVehicles().containsKey(vehicleId)){
			throw new IllegalArgumentException("You have configured agent " + plan.getPerson().getId() + " to be active for charging" +
					", and the mode " + chargingMode + " to be an electric vehicle mode, but the vehicle " + vehicleId + " is not an electric vehicle. " +
					"In order to change that, you can call VehicleUtils.setHbefaTechnology(vehicle.getType().getEngineInformation(), ElectricFleetUtils.EV_ENGINE_HBEFA_TECHNOLOGY (see ElectricFleetUtils)." );
		};
		ElectricVehicle vehicle = electricFleet.getElectricVehicles().get(vehicleId);
		return vehicle;
	}

	private void processPluggingProcesses(double now) {
		Iterator<ChargingProcess> iterator = plugging.values().iterator();

		while (iterator.hasNext()) {
			ChargingProcess process = iterator.next();

			if (!process.isSubmitted) {
				Double personMaximumQueueWaitTime = getMaximumQueueTime(
						((HasModifiablePlan) process.agent).getModifiablePlan()
								.getPerson());

				if (personMaximumQueueWaitTime == null) {
					personMaximumQueueWaitTime = maximumQueueWaitTime;
				}

				// add vehicle to charger, it will be either queued or plugged
				ChargingStrategy chargingStrategy = chargingStrategyFactory
						.createStrategy(process.currentSlot.charger().getSpecification(), process.vehicle);
				process.currentSlot.charger().getLogic().addVehicle(process.vehicle, chargingStrategy, now);
				process.latestPlugTime = now + personMaximumQueueWaitTime;
				process.isSubmitted = true;
			} else if (process.isPlugged) {
				// vehicle has been plugged -> continue to the main activity

				if (process.isWholeDay) {
					// do nothing, vehicle stays plugged the whole day
				} else if (process.isOvernight) {
					// vehicle was plugged overnight, reset end time of the first activity after
					// which the vehilce will be picked up and schedule the pickup walk
					double plannedEndTime = (Double) process.currentSlot.endActivity().getAttributes()
							.getAttribute(INITIAL_ACTIVITY_END_TIME_ATTRIBUTE);
					process.currentSlot.endActivity().setEndTime(Math.max(now, plannedEndTime));

					// following only necessary if this is the very first activity of the day
					WithinDayAgentUtils.resetCaches(process.agent);
					WithinDayAgentUtils.rescheduleActivityEnd(process.agent, qsim);

					chargingScheduler.scheduleUnplugActivityAfterOvernightCharge(process.agent,
							process.currentSlot.endActivity(), process.currentSlot.charger());
					} else {
						// stadard case, we are in a plug activity, need to end it, and let agent to to
						// main activity
						Activity plugActivity = (Activity) WithinDayAgentUtils.getCurrentPlanElement(process.agent);
						Preconditions.checkState(plugActivity.getType().equals(PLUG_ACTIVITY_TYPE));

					// end activity
					plugActivity.setEndTime(now);
					WithinDayAgentUtils.resetCaches(process.agent);
					WithinDayAgentUtils.rescheduleActivityEnd(process.agent, qsim);

					if (process.currentSlot.isLegBased()) {
						// schedule unplug at the charger then continue to main activity
						chargingScheduler.scheduleUnplugActivityAtCharger(process.agent,
								process.currentSlot.duration());
					} else {
						// walk to main activity, perform it, walk back to charger and unplug
							chargingScheduler.scheduleUntilUnplugActivity(process.agent,
									process.currentSlot.startActivity(),
									process.currentSlot.endActivity());
							if (futureChargingBehaviourModel != null) {
								process.activityChargingEndTime = determineActivityChargingEndTime(process);
							}
						}
					}

				active.put(process.agent.getId(), process);
				iterator.remove();
			} else if (process.isQueued) {
				if (now > process.latestPlugTime) {
					// remove vehicle from charger
					process.currentSlot.charger().getLogic().removeVehicle(process.vehicle, now);

					// remove from plugging processes
					iterator.remove();

					eventsManager
							.processEvent(
									new AbortChargingAttemptEvent(now, process.agent.getId(), process.vehicle.getId()));

					if (process.isWholeDay || process.isOvernight) {
						// did not succeed charging overnight
						// agent may be in any potential state along the plan

						// send event for scoring
						eventsManager.processEvent(
								new AbortChargingProcessEvent(now, process.agent.getId(), process.vehicle.getId()));

						if (performAbort) {
							// abort the agent
							process.currentSlot.endActivity().setEndTime(Double.POSITIVE_INFINITY);
							WithinDayAgentUtils.resetCaches(process.agent);
							WithinDayAgentUtils.rescheduleActivityEnd(process.agent, qsim);

							process.agent.setStateToAbort(now);
							internalInterface.arrangeNextAgentState(process.agent);
						} else if (process.isOvernight) {
							Activity endActivity = process.currentSlot.endActivity();
							double initialEndTime = (Double) endActivity.getAttributes()
									.getAttribute(INITIAL_ACTIVITY_END_TIME_ATTRIBUTE);

							// end current plug activity
							endActivity.setEndTime(Math.max(now, initialEndTime));
							WithinDayAgentUtils.resetCaches(process.agent);
							WithinDayAgentUtils.rescheduleActivityEnd(process.agent, qsim);

							chargingScheduler.scheduleAccessAfterOvernightCharge(process.agent,
									process.currentSlot.endActivity(),
									process.currentSlot.charger());
						}
					} else {
						// stadnard case
						Activity plugActivity = (Activity) WithinDayAgentUtils
								.getCurrentPlanElement(process.agent);
						Preconditions.checkState(plugActivity.getType().equals(PLUG_ACTIVITY_TYPE));

						// reset charging process
						process.attemptIndex++;

						// try to find next charger
						Plan plan = WithinDayAgentUtils.getModifiablePlan(process.agent);
						ChargingAlternative alternative = alternativeProvider.findAlternative(now,
								plan.getPerson(),
								plan,
								process.vehicle, process.initialSlot, process.trace);

						if (alternative != null) {
							// found an alternative charger
							if (process.currentSlot.isLegBased() && !alternative.isLegBased()) {
								throw new IllegalStateException(
										"Cannot switch from a leg-based charging slot to an activity-based alternative because activities are not known");
							}

							// end current plug activity
							plugActivity.setEndTime(now);
							WithinDayAgentUtils.resetCaches(process.agent);
							WithinDayAgentUtils.rescheduleActivityEnd(process.agent, qsim);

							// drive to the next charger and schedule a plug activity
							plugActivity = chargingScheduler.scheduleSubsequentPlugActivity(process.agent,
									plugActivity, alternative.charger(), now);
							plugActivity.getAttributes().putAttribute(CHARGING_PROCESS_ATTRIBUTE, process);

							// reset process for next attempt
							process.currentSlot = new ChargingSlot(process.currentSlot.startActivity(),
									process.currentSlot.endActivity(),
									process.currentSlot.leg(), alternative.duration(),
									alternative.charger());
							process.isSubmitted = false;
							process.isPlugged = false;
							process.isQueued = false;
							process.trace.add(alternative);

							// send event for scoring
							eventsManager.processEvent(
									new StartChargingAttemptEvent(now, process.agent.getId(), process.vehicle.getId(),
											alternative.charger().getId(), process.attemptIndex, process.processIndex,
											alternative.isLegBased(), false, alternative.duration()));
						} else {
							// send event for scoring
							eventsManager.processEvent(
									new AbortChargingProcessEvent(now, process.agent.getId(), process.vehicle.getId()));

							if (performAbort) {
								// we abort the agent
								plugActivity.setEndTime(Double.POSITIVE_INFINITY);
								WithinDayAgentUtils.resetCaches(process.agent);
								WithinDayAgentUtils.rescheduleActivityEnd(process.agent, qsim);

								process.agent.setStateToAbort(now);
								internalInterface.arrangeNextAgentState(process.agent);
							} else {
								// end current plug activity
								plugActivity.setEndTime(now);
								WithinDayAgentUtils.resetCaches(process.agent);
								WithinDayAgentUtils.rescheduleActivityEnd(process.agent, qsim);

								chargingScheduler.scheduleDriveToNextActivity(process.agent);
							}
						}
					}
				}
			} // else: we are waiting to be queued or plugged, don't do anything
		}

	}

	private void processActivityChargingDeadlines(double now) {
		for (ChargingProcess process : active.values()) {
			if (process.chargingEndedBeforePlannedUnplug || Double.isNaN(process.activityChargingEndTime)
					|| now < process.activityChargingEndTime) {
				continue;
			}
			if (isVehiclePlugged(process)) {
				process.currentSlot.charger().getLogic().removeVehicle(process.vehicle, now);
			}
			process.chargingEndedBeforePlannedUnplug = true;
		}
	}

	private double determineActivityChargingEndTime(ChargingProcess process) {
		if (process.currentSlot == null || process.currentSlot.isLegBased() || process.currentSlot.endActivity() == null
				|| process.isOvernight || process.isWholeDay) {
			return Double.NaN;
		}
		OptionalTime endTime = timeInterpretation.decideOnActivityEndTimeAlongPlan(process.currentSlot.endActivity(),
				WithinDayAgentUtils.getModifiablePlan(process.agent));
		return endTime.isDefined() ? endTime.seconds() : Double.NaN;
	}

	private boolean isVehiclePlugged(ChargingProcess process) {
		return process.currentSlot != null && process.currentSlot.charger() != null
				&& process.currentSlot.charger().getLogic().getPluggedVehicles().stream()
						.anyMatch(candidate -> process.vehicle.getId().equals(candidate.ev().getId()));
	}

	private void processUnpluggingProcesses(double now) {
		Iterator<ChargingProcess> iterator = unplugging.values().iterator();

		while (iterator.hasNext()) {
			ChargingProcess process = iterator.next();

			// remove vehicle from charger, but may already be done
			if (isVehiclePlugged(process)) {
				process.currentSlot.charger().getLogic().removeVehicle(process.vehicle, now);
			} else if (!process.chargingEndedBeforePlannedUnplug) {
				logger.warn(String.format(
						"Agent %s tried to unplug vehicle %s at charger %s, but was already unplugged. Is the correct ChargingStrategy configured?",
						process.agent.getId().toString(), process.vehicle.getId().toString(),
						process.currentSlot.charger().getId().toString()));
			}

			Activity unplugActivity = (Activity) WithinDayAgentUtils.getCurrentPlanElement(process.agent);
			Preconditions.checkState(unplugActivity.getType().equals(UNPLUG_ACTIVITY_TYPE));

			unplugActivity.setEndTime(now);
			WithinDayAgentUtils.resetCaches(process.agent);
			WithinDayAgentUtils.rescheduleActivityEnd(process.agent, qsim);

			chargingScheduler.scheduleDriveToNextActivity(process.agent);

			eventsManager
					.processEvent(new FinishChargingAttemptEvent(now, process.agent.getId(), process.vehicle.getId()));
			eventsManager
					.processEvent(new FinishChargingProcessEvent(now, process.agent.getId(), process.vehicle.getId()));

			iterator.remove();
		}
	}

	// BOILERPLATE

	@Override
	public void afterSim() {
	}

	private InternalInterface internalInterface;

	@Override
	public void setInternalInterface(InternalInterface internalInterface) {
		this.internalInterface = internalInterface;
	}

	/**
	 * Checks whether a person is managed by within-day electric vehicle charging
	 */
	static public boolean isActive(Person person) {
		Boolean isActive = (Boolean) person.getAttributes().getAttribute(ACTIVE_PERSON_ATTRIBUTE);
		return isActive != null && isActive;
	}

	/**
	 * Sets a person to be active in within-day electric vehicle charging or not
	 */
	static public void setActive(Person person, boolean isActive) {
		person.getAttributes().putAttribute(ACTIVE_PERSON_ATTRIBUTE, isActive);
	}

	/**
	 * Activates a person for within-day electric vehicle charging
	 */
	static public void activate(Person person) {
		setActive(person, true);
	}

	/**
	 * Retrieves the maximum queue time for a person before an attempt is aborted
	 */
	static public Double getMaximumQueueTime(Person person) {
		return (Double) person.getAttributes().getAttribute(MAXIMUM_QUEUE_TIME_PERSON_ATTRIBUTE);
	}

	/**
	 * Sets the maximum queue time for a person before an attempt is aborted
	 */
	static public void setMaximumQueueTime(Person person, double maximumQueueTime) {
		person.getAttributes().putAttribute(MAXIMUM_QUEUE_TIME_PERSON_ATTRIBUTE, maximumQueueTime);
	}

	/**
	 * Determines whether an activity type is managed in a special way by within-day
	 * electric vehicle charging
	 */
	static public boolean isManagedActivityType(String activityType) {
		return activityType.equals(PLUG_ACTIVITY_TYPE) || activityType.equals(UNPLUG_ACTIVITY_TYPE)
				|| activityType.equals(WAIT_ACTIVITY_TYPE) || activityType.equals(ACCESS_ACTIVITY_TYPE);
	}
}
