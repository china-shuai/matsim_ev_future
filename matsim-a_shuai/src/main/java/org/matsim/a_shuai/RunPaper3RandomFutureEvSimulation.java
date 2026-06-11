package org.matsim.a_shuai;

/**
 * Runs the Paper 3 Random-assignment future EV charging simulation.
 */
public class RunPaper3RandomFutureEvSimulation {
	private static final String DEFAULT_CONFIG =
			"/Users/S4065267/Simulation_paper3/input/matsim_input_21pct_random_10apa/config_paper3_random.xml";
	private static final String DEFAULT_OUTPUT =
			"/Users/S4065267/Simulation_paper3/output/output_random_21pct_10apa_test";

	public static void main(String[] args) {
		String config = args.length > 0 ? args[0] : DEFAULT_CONFIG;
		String output = args.length > 1 ? args[1] : DEFAULT_OUTPUT;
		RunFutureEvChargingBehaviourSimulation.main(new String[] { config, output });
	}
}
