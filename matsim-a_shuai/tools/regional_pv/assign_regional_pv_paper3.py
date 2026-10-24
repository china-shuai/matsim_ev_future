#!/usr/bin/env python3
"""Paper 3 v2: assign postcode-patterned house PV to the scenario agent tables.

Each agent table (agents_<U>-<H>-<A>.csv, one row per simulated EV agent) must contain
person_id, src_id, copy, cls ('house'/'apt') and group. src_id is the agent in the 10% synthetic EV
population; clones (copy > 0) are treated as separate households that keep the source postcode.
Household and postcode come from the AToM pipeline outputs (ev_population_person.csv,
ev_population_household.csv).

The `group` column is overwritten with HOUSE_WITH_PV / HOUSE_NO_PV for house agents; the previous
label is kept once in `group_randomPV`. Rebuild plans/vehicles/chargers afterwards.

Example (from Simulation_paper3/input):
  python3 <repo>/matsim-a_shuai/tools/regional_pv/assign_regional_pv_paper3.py \
      --agent-tables paper3_v2/agent_tables \
      --pipeline U32=pipeline_outputs_output10_32pct_10pctsample_dwelling_first_ap15 \
      --pipeline U21=pipeline_outputs_output10_21pct_10pctsample_dwelling_first_15apa [--check]
Requires pandas and numpy.
"""
import argparse
import glob
import json
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import regional_pv as rpv  # noqa: E402


def household_table(pipe: Path) -> pd.DataFrame:
    per = pd.read_csv(pipe / "ev_population_person.csv", usecols=["HouseholdId", "AgentId"], dtype=str)
    hh = pd.read_csv(pipe / "ev_population_household.csv", usecols=["HouseholdId", "postcode", "homeLGA"], dtype=str)
    return per.merge(hh, on="HouseholdId", how="left").set_index("AgentId")


def assign(T, m, postcodes, lgas, target, seed):
    house = T["cls"] == "house"
    hh = m["HouseholdId"].reindex(T["src_id"]).to_numpy()
    if pd.isna(hh).any():
        raise ValueError(f"{int(pd.isna(hh).sum())} agents not found in the pipeline person table")
    T["pv_household"] = np.where(T["copy"] > 0, pd.Series(hh).astype(str) + "_c" + T["copy"].astype(str), hh)
    rates = [rpv.household_rate(pc, lga, postcodes, lgas)
             for pc, lga in zip(m["postcode"].reindex(T["src_id"]), m["homeLGA"].reindex(T["src_id"]))]
    T["apvi_rate"] = [r for r, _ in rates]
    T["pv_rate_source"] = [s for _, s in rates]
    agents = T[house].groupby("pv_household").size().to_dict()
    rate_by_hh = T[house].groupby("pv_household")["apvi_rate"].first().to_dict()
    n_target = round(target * sum(agents.values()))
    selected = rpv.choose_households(agents, rate_by_hh, n_target, seed)
    new = T["group"].copy()
    new[house] = np.where(T.loc[house, "pv_household"].isin(selected), "HOUSE_WITH_PV", "HOUSE_NO_PV")
    info = dict(house_agents=int(house.sum()), pv_agents=int((new[house] == "HOUSE_WITH_PV").sum()),
                lga_fallback_agents=int((T.loc[house, "pv_rate_source"] == "lga_fallback").sum()),
                logit_intercept=rpv.calibrated_intercept(agents, rate_by_hh, target))
    return new, info


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--agent-tables", type=Path, required=True)
    ap.add_argument("--pipeline", action="append", required=True, metavar="UPTAKE=DIR",
                    help="pipeline output folder per uptake prefix, e.g. U32=pipeline_outputs_...")
    ap.add_argument("--target", type=float, default=0.85)
    ap.add_argument("--seed", type=int, default=2025)
    ap.add_argument("--check", action="store_true", help="compare with current labels, write nothing")
    a = ap.parse_args()
    pipes = {k: household_table(Path(v)) for k, v in (p.split("=", 1) for p in a.pipeline)}
    postcodes, lgas = rpv.read_rates()
    summary = {}
    for f in sorted(glob.glob(str(a.agent_tables / "agents_*.csv"))):
        scen = os.path.basename(f)[len("agents_"):-len(".csv")]
        T = pd.read_csv(f, dtype={"src_id": str, "person_id": str})
        if "group_randomPV" not in T:
            T["group_randomPV"] = T["group"]
        new, info = assign(T, pipes[scen.split("-")[0]], postcodes, lgas, a.target, a.seed)
        info["pv_share"] = info["pv_agents"] / info["house_agents"]
        if a.check:
            info["identical_to_current"] = bool((new == T["group"]).all())
        else:
            T["group"] = new
            T.to_csv(f, index=False)
        summary[scen] = info
        print(scen, json.dumps(info))
    if not a.check:
        meta = dict(method="APVI 2025 postcode house-PV density as spatial weights; VIC LGA fallback; "
                           "exact house-agent target; household-consistent; clones are separate households",
                    target=a.target, seed=a.seed, scenarios=summary)
        (a.agent_tables / "pv_assignment_summary.json").write_text(json.dumps(meta, indent=2) + "\n")


if __name__ == "__main__":
    main()
