/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2015 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.contrib.ev.discharging;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.HasLinkId;
import org.matsim.api.core.v01.events.HasVehicleId;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.ev.fleet.ElectricFleet;
import org.matsim.contrib.ev.fleet.ElectricVehicle;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.MobsimScopeEventHandler;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.interfaces.MobsimEngine;
import org.matsim.vehicles.Vehicle;

import com.google.inject.Inject;

/**
 * Because in QSim vehicles enter and leave traffic at the end of links, we skip the first link when
 * calculating the drive-related energy consumption. However, the time spent on the first link is used by the time-based
 * idle discharge process (see {@link IdleDischargingHandler}).
 */
public final class DriveDischargingHandler
	implements LinkLeaveEventHandler, VehicleEntersTrafficEventHandler, VehicleLeavesTrafficEventHandler, MobsimScopeEventHandler, MobsimEngine {

	private static final Logger LOG = LogManager.getLogger(DriveDischargingHandler.class);

	private static class EvDrive {
		private final Id<Vehicle> vehicleId;
		private final ElectricVehicle ev;
		private double movedOverNodeTime;

		public EvDrive(Id<Vehicle> vehicleId, ElectricVehicle ev) {
			this.vehicleId = vehicleId;
			this.ev = ev;
			movedOverNodeTime = Double.NaN;
		}

		private boolean isOnFirstLink() {
			return Double.isNaN(movedOverNodeTime);
		}
	}

	private final Network network;
	private final EventsManager eventsManager;
	private final Map<Id<Vehicle>, ? extends ElectricVehicle> eVehicles;
	private final Map<Id<Vehicle>, EvDrive> evDrives;

	// Paper 3 fix (2026-11-05): enters-traffic, link-leave and leaves-traffic events are kept in ONE queue and are
	// all processed in the main thread, in the order in which the handler received them. Previously the EvDrive
	// state was created in the event-handling thread on VehicleEntersTraffic while the previous trip's
	// VehicleLeavesTraffic was processed (and the state removed) in the main thread. For a trip starting one second
	// after the previous trip ended (zero-duration activity in between) the main thread could remove the NEW trip's
	// state, so the whole trip was driven without discharge. The loss depended on event-handling speed and was
	// therefore larger on iterations without event writing (home charging +2-5% on event-writing iterations).
	private final Queue<Event> driveEvents = new ConcurrentLinkedQueue<>();
	private final QSim qsim;
	@Inject
	DriveDischargingHandler(QSim qsim, ElectricFleet data, Network network, EventsManager eventsManager) {
		this.qsim = qsim;
		this.network = network;
		this.eventsManager = eventsManager;
		eVehicles = data.getElectricVehicles();
		evDrives = new ConcurrentHashMap<>(eVehicles.size() / 10);
	}

	@Override
	public void handleEvent(VehicleEntersTrafficEvent event) {
		if (eVehicles.containsKey(event.getVehicleId())) {// handle only our EVs
			driveEvents.add(event);
		}
	}

	@Override
	public void handleEvent(LinkLeaveEvent event) {
		if (eVehicles.containsKey(event.getVehicleId())) {// handle only our EVs
			driveEvents.add(event);
		}
	}

	@Override
	public void handleEvent(VehicleLeavesTrafficEvent event) {
		if (eVehicles.containsKey(event.getVehicleId())) {// handle only our EVs
			driveEvents.add(event);
		}
	}

	@Override
	public void onPrepareSim() {
	}

	@Override
	public void afterSim() {
		// process all remaining events
		handleQueuedEvents(Double.POSITIVE_INFINITY);
	}

	@Override
	public void setInternalInterface(InternalInterface internalInterface) {
	}

	@Override
	public void doSimStep(double time) {
		handleQueuedEvents(time);
	}

	private void handleQueuedEvents(double time) {
		// Events are processed in the main thread (instead of the event handling threads) to avoid races on the
		// battery. Only events from previous time steps are processed; events of the current step may still be
		// arriving from the event-handling threads.
		while (!driveEvents.isEmpty()) {
			Event event = driveEvents.peek();
			if (event.getTime() >= time) {
				break;
			}
			driveEvents.remove();

			if (event instanceof VehicleEntersTrafficEvent enters) {
				Id<Vehicle> vehicleId = enters.getVehicleId();
				evDrives.put(vehicleId, new EvDrive(vehicleId, eVehicles.get(vehicleId)));
				continue;
			}

			boolean leftTraffic = event instanceof VehicleLeavesTrafficEvent;
			Id<Vehicle> vehicleId = ((HasVehicleId) event).getVehicleId();
			Id<Link> linkId = ((HasLinkId) event).getLinkId();
			var evDrive = dischargeVehicle(vehicleId, linkId, event.getTime(), Math.min(time, qsim.getSimTimer().getTimeOfDay()));
			if (evDrive == null) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("No EV state found for vehicle {} on link {} at time {}.", vehicleId, linkId, event.getTime());
				}
				continue;
			}
			if (leftTraffic) {
				evDrives.remove(evDrive.vehicleId);
			} else {
				evDrive.movedOverNodeTime = event.getTime();
			}
		}
	}

	private EvDrive dischargeVehicle(Id<Vehicle> vehicleId, Id<Link> linkId, double eventTime, double now) {
		EvDrive evDrive = evDrives.get(vehicleId);
		if (evDrive == null) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("Missing EV state for vehicle {} on link {} at time {}. Most likely the vehicle already left traffic in the previous step.",
						vehicleId, linkId, now);
			}
			return null;
		}
		if (!evDrive.isOnFirstLink()) {// skip the first link
			Link link = network.getLinks().get(linkId);
			double tt = eventTime - evDrive.movedOverNodeTime;
			ElectricVehicle ev = evDrive.ev;
			double energy = ev.getDriveEnergyConsumption().calcEnergyConsumption(link, tt, eventTime - tt) + ev.getAuxEnergyConsumption()
				.calcEnergyConsumption(eventTime - tt, tt, linkId);
			//Energy consumption may be negative on links with negative slope
			//===============================================================
			//Added to handle cases when the negative energy discharged would surpass the battery capacity
			//The new resulting energy should mean that the battery stays at the battery capacity when the energy is negative
			if(ev.getBattery().getCharge() - energy > ev.getBattery().getCapacity()){
				energy = ev.getBattery().getCharge() - ev.getBattery().getCapacity();
			}
			//===================================================================
			ev.getBattery()
				.dischargeEnergy(energy,
					missingEnergy -> eventsManager.processEvent(new MissingEnergyEvent(now, ev.getId(), link.getId(), missingEnergy)));
			eventsManager.processEvent(new DrivingEnergyConsumptionEvent(now, vehicleId, linkId, energy, ev.getBattery().getCharge()));
		}
		return evDrive;
	}
}
