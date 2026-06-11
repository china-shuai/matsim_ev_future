package org.matsim.contrib.ev.behavior;

import java.util.Objects;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import jakarta.annotation.Nullable;

/**
 * Minimal agent descriptor used by the charging behaviour model.
 */
public final class Agent {
	private final GroupType group;
	private final boolean hasHomeCharger;
	@Nullable
	private final Id<Person> personId;

	/**
	 * @param group
	 *            dwelling type group the agent belongs to
	 * @param hasHomeCharger
	 *            whether the agent owns a home charger
	 */
	public Agent(GroupType group, boolean hasHomeCharger) {
		this(group, hasHomeCharger, null);
	}

	public Agent(GroupType group, boolean hasHomeCharger, @Nullable Id<Person> personId) {
		this.group = Objects.requireNonNull(group, "group");
		this.hasHomeCharger = hasHomeCharger;
		this.personId = personId;
	}

	public GroupType getGroup() {
		return group;
	}

	public boolean hasHomeCharger() {
		return hasHomeCharger;
	}

	@Nullable
	public Id<Person> getPersonId() {
		return personId;
	}
}

