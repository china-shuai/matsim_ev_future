package org.matsim.contrib.ev.withinday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.ev.behavior.Agent;
import org.matsim.contrib.ev.behavior.FutureChargingActivity;
import org.matsim.contrib.ev.behavior.FutureChargingActivityLabel;
import org.matsim.contrib.ev.behavior.FutureChargingBehaviourConfigGroup;
import org.matsim.contrib.ev.behavior.FutureChargingBehaviourModel;
import org.matsim.contrib.ev.behavior.FutureChargingBehaviourParameters;
import org.matsim.contrib.ev.behavior.FutureChargingSupplyType;
import org.matsim.contrib.ev.behavior.GroupType;
import org.matsim.contrib.ev.behavior.TimeBand;

public class FutureChargingBehaviourModelTest {
	@Test
	public void testChargingStartProbabilityIsIndependentOfSupplyOptions() {
		FutureChargingBehaviourConfigGroup c1 = new FutureChargingBehaviourConfigGroup();
		c1.setWorkplaceChargingAvailable(false);
		FutureChargingBehaviourModel fallbackOnly = new FutureChargingBehaviourModel(c1,
				FutureChargingBehaviourParameters.createDefault());

		FutureChargingBehaviourConfigGroup c2 = new FutureChargingBehaviourConfigGroup();
		c2.setWorkplaceChargingAvailable(true);
		FutureChargingBehaviourModel sharedAccess = new FutureChargingBehaviourModel(c2,
				FutureChargingBehaviourParameters.createDefault());
		sharedAccess.setSupplyPreference(GroupType.APARTMENT, FutureChargingSupplyType.WORKPLACE, 2.0);

		Agent agent = new Agent(GroupType.APARTMENT, false);
		FutureChargingActivity fallbackActivity = new FutureChargingActivity(FutureChargingActivityLabel.WORK,
				TimeBand.MIDDAY_10_16, 0.8, null,
				fallbackOnly.createFeasibleSupplyTypes(FutureChargingActivityLabel.WORK, false, false));
		FutureChargingActivity sharedActivity = new FutureChargingActivity(FutureChargingActivityLabel.WORK,
				TimeBand.MIDDAY_10_16, 0.8, null,
				sharedAccess.createFeasibleSupplyTypes(FutureChargingActivityLabel.WORK, false, false));

		assertEquals(fallbackOnly.computeChargingProbability(agent, fallbackActivity),
				sharedAccess.computeChargingProbability(agent, sharedActivity), 1e-12);
	}

	@Test
	public void testMandatoryChargingAndSupplyChoice() {
		FutureChargingBehaviourConfigGroup cfg = new FutureChargingBehaviourConfigGroup();
		cfg.setDestinationChargingAvailable(true);
		FutureChargingBehaviourModel model = new FutureChargingBehaviourModel(cfg,
				FutureChargingBehaviourParameters.createDefault());
		model.setSupplyPreference(GroupType.HOUSE_NO_PV, FutureChargingSupplyType.DESTINATION, 10.0);

		Agent agent = new Agent(GroupType.HOUSE_NO_PV, false);
		FutureChargingActivity activity = new FutureChargingActivity(FutureChargingActivityLabel.SHOP,
				TimeBand.EVENING_16_22, 0.1, null,
				EnumSet.of(FutureChargingSupplyType.DESTINATION, FutureChargingSupplyType.FAST));

		var decision = model.makeChargingDecision(agent, activity, new Random(1));
		assertEquals(1.0, decision.getProbability(), 1e-9);
		assertTrue(decision.willCharge());
		assertEquals(FutureChargingSupplyType.DESTINATION, decision.getSupplyType());
	}

	@Test
	public void testPublicFeasibleSupplyTypesDoNotUseFallback() {
		FutureChargingBehaviourConfigGroup cfg = new FutureChargingBehaviourConfigGroup();
		cfg.setPublicFastFallback(false);
		FutureChargingBehaviourModel model = new FutureChargingBehaviourModel(cfg,
				FutureChargingBehaviourParameters.createDefault());

		assertEquals(EnumSet.of(FutureChargingSupplyType.HOME),
				model.createFeasibleSupplyTypes(FutureChargingActivityLabel.HOME, true, false));
		assertTrue(model.createFeasiblePublicSupplyTypes(FutureChargingActivityLabel.HOME, true, false).isEmpty());
		assertTrue(model.createFeasiblePublicSupplyTypes(FutureChargingActivityLabel.WORK, false, false).isEmpty());
		cfg.setWorkplaceChargingAvailable(true);
		cfg.setDestinationChargingAvailable(true);
		FutureChargingBehaviourModel specificAccessModel = new FutureChargingBehaviourModel(cfg,
				FutureChargingBehaviourParameters.createDefault());
		assertTrue(specificAccessModel.createFeasiblePublicSupplyTypes(FutureChargingActivityLabel.WORK, false, false)
				.isEmpty());
		assertTrue(specificAccessModel.createFeasiblePublicSupplyTypes(FutureChargingActivityLabel.SHOP, false, false)
				.isEmpty());

		FutureChargingBehaviourConfigGroup fallbackCfg = new FutureChargingBehaviourConfigGroup();
		FutureChargingBehaviourModel fallbackModel = new FutureChargingBehaviourModel(fallbackCfg,
				FutureChargingBehaviourParameters.createDefault());

		assertEquals(EnumSet.of(FutureChargingSupplyType.FAST),
				fallbackModel.createFeasiblePublicSupplyTypes(FutureChargingActivityLabel.HOME, false, false));
	}

	@Test
	public void testOpportunityValueIsStableForLargeSupplyPreferences() {
		// Large α values (e.g. α=800) would overflow the naive exp/log implementation.
		// The log-sum-exp form must remain finite and numerically reasonable.
		FutureChargingBehaviourConfigGroup cfg = new FutureChargingBehaviourConfigGroup();
		cfg.setWorkplaceChargingAvailable(true);
		FutureChargingBehaviourModel model = new FutureChargingBehaviourModel(cfg,
				FutureChargingBehaviourParameters.createDefault());
		model.setSupplyPreference(GroupType.APARTMENT, FutureChargingSupplyType.WORKPLACE, 800.0);
		model.setSupplyPreference(GroupType.APARTMENT, FutureChargingSupplyType.FAST, 0.0);

		FutureChargingActivity activity = new FutureChargingActivity(FutureChargingActivityLabel.WORK,
				TimeBand.MIDDAY_10_16, 0.5, null,
				EnumSet.of(FutureChargingSupplyType.WORKPLACE, FutureChargingSupplyType.FAST));

		double opportunityValue = model.computeOpportunityValue(GroupType.APARTMENT, activity);
		assertTrue(Double.isFinite(opportunityValue), "log-sum-exp must stay finite for α=800");
		// log(exp(800) + exp(0)) - 0 ≈ 800 (since exp(0) is dwarfed). Tolerance generous.
		assertEquals(800.0, opportunityValue, 1e-6);
	}

	@Test
	public void testLogSumExpEquivalenceForModeratePreferences() {
		// Smoke-check that the stable formulation matches the naive softmax for small α
		// where both representations are valid.
		FutureChargingBehaviourConfigGroup cfg = new FutureChargingBehaviourConfigGroup();
		cfg.setDestinationChargingAvailable(true);
		FutureChargingBehaviourModel model = new FutureChargingBehaviourModel(cfg,
				FutureChargingBehaviourParameters.createDefault());
		model.setSupplyPreference(GroupType.APARTMENT, FutureChargingSupplyType.DESTINATION, 1.5);
		model.setSupplyPreference(GroupType.APARTMENT, FutureChargingSupplyType.FAST, 0.7);

		FutureChargingActivity activity = new FutureChargingActivity(FutureChargingActivityLabel.SHOP,
				TimeBand.EVENING_16_22, 0.5, null,
				EnumSet.of(FutureChargingSupplyType.DESTINATION, FutureChargingSupplyType.FAST));

		double computed = model.computeOpportunityValue(GroupType.APARTMENT, activity);
		double expected = Math.log(Math.exp(1.5) + Math.exp(0.7)) - 0.7;
		assertEquals(expected, computed, 1e-12);
	}

	@Test
	public void testSupplyTypeSamplingIsStableForLargePreferences() {
		// chooseSupplyType uses softmax sampling; with α=600 the naive form returns
		// Infinity / Infinity = NaN, breaking the cumulative draw. The max-shift version
		// must consistently pick the dominating alternative.
		FutureChargingBehaviourConfigGroup cfg = new FutureChargingBehaviourConfigGroup();
		cfg.setDestinationChargingAvailable(true);
		FutureChargingBehaviourModel model = new FutureChargingBehaviourModel(cfg,
				FutureChargingBehaviourParameters.createDefault());
		model.setSupplyPreference(GroupType.HOUSE_NO_PV, FutureChargingSupplyType.DESTINATION, 600.0);
		model.setSupplyPreference(GroupType.HOUSE_NO_PV, FutureChargingSupplyType.FAST, 0.0);

		Agent agent = new Agent(GroupType.HOUSE_NO_PV, false);
		FutureChargingActivity activity = new FutureChargingActivity(FutureChargingActivityLabel.SHOP,
				TimeBand.EVENING_16_22, 0.05, null,
				EnumSet.of(FutureChargingSupplyType.DESTINATION, FutureChargingSupplyType.FAST));

		Random rng = new Random(7);
		for (int i = 0; i < 50; i++) {
			var decision = model.makeChargingDecision(agent, activity, rng);
			assertTrue(decision.willCharge());
			assertEquals(FutureChargingSupplyType.DESTINATION, decision.getSupplyType(),
					"draw " + i + " should pick the dominating alternative");
		}
	}

	@Test
	public void testLatentServiceRatesAreConfigurable() {
		FutureChargingBehaviourConfigGroup cfg = new FutureChargingBehaviourConfigGroup();
		cfg.setLatentWorkplaceServiceRate(1.0);
		cfg.setLatentDestinationServiceRate(0.5);
		FutureChargingBehaviourModel model = new FutureChargingBehaviourModel(cfg,
				FutureChargingBehaviourParameters.createDefault());

		assertEquals(1.0, model.getLatentWorkplaceServiceRate(), 1e-9);
		assertEquals(0.5, model.getLatentDestinationServiceRate(), 1e-9);
		assertTrue(model.isLatentDemandSupplyType(FutureChargingSupplyType.WORKPLACE));
		assertTrue(model.isLatentDemandSupplyType(FutureChargingSupplyType.DESTINATION));
		assertTrue(model.isLatentDemandSupplyType(FutureChargingSupplyType.FAST));
	}
}
