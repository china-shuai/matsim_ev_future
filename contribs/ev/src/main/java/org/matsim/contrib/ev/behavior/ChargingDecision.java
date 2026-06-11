package org.matsim.contrib.ev.behavior;

import java.util.Objects;

/**
 * Result of a charging decision including probability, binary outcome and charger type.
 */
public final class ChargingDecision {
	private final double probability;
	private final boolean willCharge;
	private final String chargerType;
	private final boolean mandatory;

	public ChargingDecision(double probability, boolean willCharge, String chargerType, boolean mandatory) {
		this.probability = probability;
		this.willCharge = willCharge;
		this.chargerType = Objects.requireNonNull(chargerType, "chargerType");
		this.mandatory = mandatory;
	}

	public double getProbability() {
		return probability;
	}

	public boolean willCharge() {
		return willCharge;
	}

	public String getChargerType() {
		return chargerType;
	}

	public boolean isMandatory() {
		return mandatory;
	}

	@Override
	public String toString() {
		return "ChargingDecision{" + "probability=" + probability + ", willCharge=" + willCharge + ", chargerType='"
				+ chargerType + '\'' + ", mandatory=" + mandatory + '}';
	}
}

