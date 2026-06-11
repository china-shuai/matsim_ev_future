package org.matsim.contrib.ev.behavior;

/**
 * Charging supply alternatives used by the future charging-access model.
 */
public enum FutureChargingSupplyType {
	HOME("home"),
	WORKPLACE("workplace"),
	DESTINATION("destAC"),
	FAST("DCFC");

	private final String chargerType;

	FutureChargingSupplyType(String chargerType) {
		this.chargerType = chargerType;
	}

	public String getChargerType() {
		return chargerType;
	}
}
