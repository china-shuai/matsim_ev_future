#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

: "${MAVEN_OPTS=-Xmx12g}"
export MAVEN_OPTS

MAIN_CLASS="org.matsim.a_shuai.RunPaper3RandomFutureEvSimulation"
INPUT_ROOT="/Users/S4065267/Simulation_paper3/paper3_random_8scenario_bundle_20260611/input"
RUN_ID="$(date '+%Y%m%d_%H%M%S')"
OUTPUT_ROOT="/Users/S4065267/Simulation_paper3/output/batch_${RUN_ID}"
LOG_DIR="${OUTPUT_ROOT}/logs"
mkdir -p "$LOG_DIR"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] BUILD current EV and Paper 3 modules"
mvn -q -pl contribs/ev,matsim-a_shuai -am -DskipTests install

run_case() {
	local label="$1"
	local config="$2"
	local output="$3"
	local log_file="${LOG_DIR}/${label}.log"

	echo "[$(date '+%Y-%m-%d %H:%M:%S')] START ${label}"
	echo "  config: ${config}"
	echo "  output: ${output}"
	echo "  log: ${log_file}"
	echo "  MAVEN_OPTS: ${MAVEN_OPTS}"

	caffeinate -dimsu mvn -q -f matsim-a_shuai/pom.xml -DskipTests exec:java \
		-Dexec.mainClass="${MAIN_CLASS}" \
		-Dexec.args="${config} ${output}" 2>&1 | tee "$log_file"

	echo "[$(date '+%Y-%m-%d %H:%M:%S')] DONE ${label}"
}

run_case "21pct_random_10apa" \
	"${INPUT_ROOT}/matsim_input_21pct_random_10apa/config_paper3_random.xml" \
	"${OUTPUT_ROOT}/output_random_21pct_10apa"

run_case "21pct_random_15apa" \
	"${INPUT_ROOT}/matsim_input_21pct_random_15apa/config_paper3_random.xml" \
	"${OUTPUT_ROOT}/output_random_21pct_15apa"

run_case "21pct_random_10apa_high_supply" \
	"${INPUT_ROOT}/matsim_input_21pct_random_10apa_high_supply/config_paper3_random_high_supply.xml" \
	"${OUTPUT_ROOT}/output_random_21pct_10apa_high_supply"

run_case "21pct_random_15apa_high_supply" \
	"${INPUT_ROOT}/matsim_input_21pct_random_15apa_high_supply/config_paper3_random_high_supply.xml" \
	"${OUTPUT_ROOT}/output_random_21pct_15apa_high_supply"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] ALL 21PCT PAPER 3 RANDOM RUNS DONE"
