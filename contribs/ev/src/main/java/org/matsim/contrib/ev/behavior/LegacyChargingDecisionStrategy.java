package org.matsim.contrib.ev.behavior;

import java.util.Objects;
import java.util.Random;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Adapts the legacy {@link ChargingBehaviourModel} to the {@link ChargingDecisionStrategy}
 * interface so the WEVC engine can use a single uniform API.
 */
@Singleton
public final class LegacyChargingDecisionStrategy implements ChargingDecisionStrategy {

	private final ChargingBehaviourModel model;

	@Inject
	public LegacyChargingDecisionStrategy(ChargingBehaviourModel model) {
		this.model = Objects.requireNonNull(model, "model");
	}

	@Override
	public void registerGroupMember(GroupType group, Id<Person> personId) {
		model.registerGroupMember(group, personId);
	}

	@Override
	public void recordChargingOutcome(GroupType group, Id<Person> personId, boolean success, boolean mandatory) {
		model.recordChargingOutcome(group, personId, success, mandatory);
	}

	@Override
	public double getAcReferencePower() {
		return model.getAcReferencePower();
	}

	@Override
	public double getDcfcReferencePower() {
		return model.getDcfcReferencePower();
	}

	@Override
	public double computeChargingProbability(ChargingDecisionContext ctx) {
		Agent agent = new Agent(ctx.group(), ctx.hasHomeCharger(), ctx.personId());
		Activity activity = new Activity(ctx.locationType(), ctx.timeBand(), ctx.soc(), ctx.canFinishNextTrip());
		return model.computeChargingProbability(agent, activity);
	}

	@Override
	public ChargingDecision makeChargingDecision(ChargingDecisionContext ctx, Random rng) {
		Agent agent = new Agent(ctx.group(), ctx.hasHomeCharger(), ctx.personId());
		Activity activity = new Activity(ctx.locationType(), ctx.timeBand(), ctx.soc(), ctx.canFinishNextTrip());
		return model.makeChargingDecision(agent, activity, rng);
	}

	@Override
	public boolean isMandatoryCharging(ChargingDecisionContext ctx) {
		Activity activity = new Activity(ctx.locationType(), ctx.timeBand(), ctx.soc(), ctx.canFinishNextTrip());
		return model.isMandatoryCharging(activity);
	}
}
