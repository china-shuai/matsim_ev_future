package org.matsim.contrib.ev.behavior;

import java.util.Objects;

/**
 * Charging-start and H/W/D/F supply-location decision.
 */
public final class FutureChargingDecision {
	private final double probability;
	private final boolean willCharge;
	private final FutureChargingSupplyType supplyType;
	private final boolean mandatory;
	private final double opportunityValue;

	public FutureChargingDecision(double probability, boolean willCharge, FutureChargingSupplyType supplyType,
			boolean mandatory, double opportunityValue) {
		this.probability = probability;
		this.willCharge = willCharge;
		this.supplyType = Objects.requireNonNull(supplyType, "supplyType");
		this.mandatory = mandatory;
		this.opportunityValue = opportunityValue;
	}

	public double getProbability() {
		return probability;
	}

	public boolean willCharge() {
		return willCharge;
	}

	public FutureChargingSupplyType getSupplyType() {
		return supplyType;
	}

	public String getChargerType() {
		return supplyType.getChargerType();
	}

	public boolean isMandatory() {
		return mandatory;
	}

	public double getOpportunityValue() {
		return opportunityValue;
	}
}
