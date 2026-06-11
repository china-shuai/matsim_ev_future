package org.matsim.contrib.ev.behavior;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Random;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Adapts the future paper-3 {@link FutureChargingBehaviourModel} to the
 * {@link ChargingDecisionStrategy} interface.
 * <p>
 * Implements the two-step feasibility computation required by the future model: the feasible
 * supply set depends on whether charging is mandatory, but mandatory-ness itself depends on the
 * current state, so we first probe with {@code mandatory=false} and then build the final activity
 * with the resolved mandatory flag.
 */
@Singleton
public final class FutureChargingDecisionStrategy implements ChargingDecisionStrategy {

	private final FutureChargingBehaviourModel model;

	@Inject
	public FutureChargingDecisionStrategy(FutureChargingBehaviourModel model) {
		this.model = Objects.requireNonNull(model, "model");
	}

	public FutureChargingBehaviourModel getModel() {
		return model;
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
		FutureChargingActivity activity = buildActivity(ctx, false);
		return model.computeChargingProbability(agent, activity);
	}

	@Override
	public ChargingDecision makeChargingDecision(ChargingDecisionContext ctx, Random rng) {
		Agent agent = new Agent(ctx.group(), ctx.hasHomeCharger(), ctx.personId());
		FutureChargingActivity probeActivity = buildActivity(ctx, false);
		boolean mandatory = model.isMandatoryCharging(probeActivity);
		FutureChargingActivity finalActivity = buildActivity(ctx, mandatory);
		FutureChargingDecision decision = model.makeChargingDecision(agent, finalActivity, rng);
		return new ChargingDecision(decision.getProbability(), decision.willCharge(), decision.getChargerType(),
				decision.isMandatory());
	}

	@Override
	public boolean isMandatoryCharging(ChargingDecisionContext ctx) {
		FutureChargingActivity probeActivity = buildActivity(ctx, false);
		return model.isMandatoryCharging(probeActivity);
	}

	@Override
	public boolean isLatentPublicDemandEnabled() {
		return model.isLatentPublicDemandEnabled();
	}

	@Override
	public boolean isUnrestrictedLatentPublicDemand() {
		return model.isUnrestrictedLatentPublicDemand();
	}

	private FutureChargingActivity buildActivity(ChargingDecisionContext ctx, boolean mandatory) {
		EnumSet<FutureChargingSupplyType> feasible = model.createFeasibleSupplyTypes(ctx.activityLabel(),
				ctx.hasHomeCharger(), mandatory);
		return new FutureChargingActivity(ctx.activityLabel(), ctx.timeBand(), ctx.soc(), ctx.canFinishNextTrip(),
				feasible);
	}
}
