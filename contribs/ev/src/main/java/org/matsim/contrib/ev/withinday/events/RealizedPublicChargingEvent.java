package org.matsim.contrib.ev.withinday.events;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.HasLinkId;
import org.matsim.api.core.v01.events.HasPersonId;
import org.matsim.api.core.v01.events.HasVehicleId;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.ev.behavior.FutureChargingSupplyType;
import org.matsim.vehicles.Vehicle;

/**
 * Records a realized workplace, destination, or public-fast charging transfer.
 * The event is emitted only after the transferred energy has been applied to the
 * simulated EV battery.
 */
public final class RealizedPublicChargingEvent extends Event
		implements HasPersonId, HasVehicleId, HasLinkId {
	public static final String EVENT_TYPE = "future_public_charging";

	private final Id<Person> personId;
	private final Id<Vehicle> vehicleId;
	private final Id<Link> linkId;
	private final FutureChargingSupplyType supplyType;
	private final double chargingStartTime;
	private final double chargingEndTime;
	private final double chargingDuration;
	private final double energy;
	private final double chargeBefore;
	private final double chargeAfter;
	private final double socBefore;
	private final double socAfter;

	public RealizedPublicChargingEvent(double time, Id<Person> personId, Id<Vehicle> vehicleId,
			Id<Link> linkId, FutureChargingSupplyType supplyType, double chargingStartTime,
			double chargingEndTime, double chargingDuration, double energy, double chargeBefore,
			double chargeAfter, double socBefore, double socAfter) {
		super(time);
		this.personId = personId;
		this.vehicleId = vehicleId;
		this.linkId = linkId;
		this.supplyType = supplyType;
		this.chargingStartTime = chargingStartTime;
		this.chargingEndTime = chargingEndTime;
		this.chargingDuration = chargingDuration;
		this.energy = energy;
		this.chargeBefore = chargeBefore;
		this.chargeAfter = chargeAfter;
		this.socBefore = socBefore;
		this.socAfter = socAfter;
	}

	@Override
	public String getEventType() {
		return EVENT_TYPE;
	}

	@Override
	public Id<Person> getPersonId() {
		return personId;
	}

	@Override
	public Id<Vehicle> getVehicleId() {
		return vehicleId;
	}

	@Override
	public Id<Link> getLinkId() {
		return linkId;
	}

	public FutureChargingSupplyType getSupplyType() {
		return supplyType;
	}

	public double getEnergy() {
		return energy;
	}

	public double getSocBefore() {
		return socBefore;
	}

	public double getSocAfter() {
		return socAfter;
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attributes = super.getAttributes();
		attributes.put("supplyType", supplyType.name());
		attributes.put("chargingStartTime", Double.toString(chargingStartTime));
		attributes.put("chargingEndTime", Double.toString(chargingEndTime));
		attributes.put("chargingDuration", Double.toString(chargingDuration));
		attributes.put("energy", Double.toString(energy));
		attributes.put("chargeBefore", Double.toString(chargeBefore));
		attributes.put("chargeAfter", Double.toString(chargeAfter));
		attributes.put("socBefore", Double.toString(socBefore));
		attributes.put("socAfter", Double.toString(socAfter));
		return attributes;
	}
}
