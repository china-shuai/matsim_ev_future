#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

: "${MAVEN_OPTS=-Xmx12g}"
export MAVEN_OPTS

MAIN_CLASS="org.matsim.a_shuai.RunPaper3RandomFutureEvSimulation"
LOG_DIR="/Users/S4065267/Simulation_paper3/output/batch_logs"
mkdir -p "$LOG_DIR"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] BUILD current EV and Paper 3 modules"
mvn -q -pl contribs/ev,matsim-a_shuai -am -DskipTests install

run_case() {
	local label="$1"
	local config="$2"
	local output="$3"
	local log_file="${LOG_DIR}/${label}_$(date '+%Y%m%d_%H%M%S').log"

	echo "[$(date '+%Y-%m-%d %H:%M:%S')] START ${label}"
	echo "  config: ${config}"
	echo "  output: ${output}"
	echo "  log: ${log_file}"
	echo "  MAVEN_OPTS: ${MAVEN_OPTS}"

	mvn -q -f matsim-a_shuai/pom.xml -DskipTests exec:java \
		-Dexec.mainClass="${MAIN_CLASS}" \
		-Dexec.args="${config} ${output}" 2>&1 | tee "$log_file"

	echo "[$(date '+%Y-%m-%d %H:%M:%S')] DONE ${label}"
}

run_case "32pct_random_15apa" \
	"/Users/S4065267/Simulation_paper3/input/matsim_input_32pct_random_15apa/config_paper3_random.xml" \
	"/Users/S4065267/Simulation_paper3/output/output_random_32pct_15apa"

run_case "32pct_random_15apa_high_supply" \
	"/Users/S4065267/Simulation_paper3/input/matsim_input_32pct_random_15apa_high_supply/config_paper3_random_high_supply.xml" \
	"/Users/S4065267/Simulation_paper3/output/output_random_32pct_15apa_high_supply"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] BOTH 32PCT 15APA RUNS DONE"
