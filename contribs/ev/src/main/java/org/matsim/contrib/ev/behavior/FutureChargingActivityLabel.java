package org.matsim.contrib.ev.behavior;

import java.util.Locale;

/**
 * Activity labels used to construct the feasible H/W/D/F charging supply set.
 */
public enum FutureChargingActivityLabel {
	HOME,
	WORK,
	SHOP,
	STUDY,
	SOCIAL_RECREATIONAL,
	OTHER,
	PERSONAL,
	PICKUP_DROPOFF_DELIVER,
	MODE_CHANGE,
	WITH_SOMEONE;

	public static FutureChargingActivityLabel fromActivityType(String type) {
		if (type == null || type.isBlank()) {
			return OTHER;
		}

		String normalized = type.toLowerCase(Locale.ROOT);
		if (normalized.contains("home") || normalized.contains("residence")) {
			return HOME;
		}
		if (normalized.contains("work") || normalized.contains("job")) {
			return WORK;
		}
		if (normalized.contains("shop") || normalized.contains("retail") || normalized.contains("buy")) {
			return SHOP;
		}
		if (normalized.contains("study") || normalized.contains("education") || normalized.contains("school")
				|| normalized.contains("university")) {
			return STUDY;
		}
		if (normalized.contains("social") || normalized.contains("recreation") || normalized.contains("leisure")
				|| normalized.contains("visit")) {
			return SOCIAL_RECREATIONAL;
		}
		if (normalized.contains("personal")) {
			return PERSONAL;
		}
		if (normalized.contains("pickup") || normalized.contains("pick-up") || normalized.contains("dropoff")
				|| normalized.contains("drop-off") || normalized.contains("deliver")) {
			return PICKUP_DROPOFF_DELIVER;
		}
		if (normalized.contains("mode") || normalized.contains("change") || normalized.contains("transfer")) {
			return MODE_CHANGE;
		}
		if (normalized.contains("with")) {
			return WITH_SOMEONE;
		}
		return OTHER;
	}
}
