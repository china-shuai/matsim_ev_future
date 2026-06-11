package org.matsim.contrib.ev.behavior;

import java.util.Random;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

/**
 * Strategy abstraction over a charging-behaviour model. WEVC engine code talks to this
 * interface only, so legacy and future-paper-3 models can be plugged in independently.
 * <p>
 * Two implementations live next to this interface:
 * <ul>
 *   <li>{@link LegacyChargingDecisionStrategy} — wraps {@link ChargingBehaviourModel}.</li>
 *   <li>{@link FutureChargingDecisionStrategy} — wraps {@link FutureChargingBehaviourModel}.</li>
 * </ul>
 * Only one strategy should be bound per Guice injector (the corresponding behaviour module
 * sets the binding on an {@code OptionalBinder<ChargingDecisionStrategy>}).
 */
public interface ChargingDecisionStrategy {

	/** Registers a potential per-day participant during {@code onPrepareSim}. */
	void registerGroupMember(GroupType group, Id<Person> personId);

	/** Records a realised charging outcome for iterative calibration. */
	void recordChargingOutcome(GroupType group, Id<Person> personId, boolean success, boolean mandatory);

	/** AC charging reference power in W (used to estimate charging duration). */
	double getAcReferencePower();

	/** DC fast-charging reference power in W (used to estimate charging duration). */
	double getDcfcReferencePower();

	/** Charging-start probability at the given context (used to argmax across candidate slots). */
	double computeChargingProbability(ChargingDecisionContext ctx);

	/** Final start + supply-type decision at the given context. */
	ChargingDecision makeChargingDecision(ChargingDecisionContext ctx, Random rng);

	/** Whether the strategy considers charging mandatory at the given context. */
	boolean isMandatoryCharging(ChargingDecisionContext ctx);

	/** Whether the strategy supports recording latent public-charging demand. */
	default boolean isLatentPublicDemandEnabled() {
		return false;
	}

	/** Whether latent public demand should be recorded irrespective of realised charging events. */
	default boolean isUnrestrictedLatentPublicDemand() {
		return false;
	}
}
