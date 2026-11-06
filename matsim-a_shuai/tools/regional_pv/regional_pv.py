"""Postcode-patterned house PV assignment (method shared by Paper 2 and Paper 3).

APVI estimates of the fraction of suitable houses with rooftop PV supply the *relative spatial
pattern* only; they are not PV adoption rates conditional on EV ownership. The total is fixed by an
assumed share of house EV agents with PV (Paper 2 and Paper 3: 85%). Households are ranked by
logit(rate) - logit(u), with u a stable per-household uniform draw, and selected until the exact
agent target is reached. All agents of a household share one label.

These functions are copied unchanged from the Paper 2 analysis script `assign_regional_pv.py`
(private repository, 2026-09-24) so that Paper 3 can be reproduced from this repository alone.
Only the Python standard library is used.
"""
from __future__ import annotations

import csv
import hashlib
import math
import re
from pathlib import Path

DATA = Path(__file__).resolve().parent / "data"
POSTCODES = DATA / "apvi_postcodes_2025.csv"
LGAS = DATA / "apvi_lgas_2025.csv"


def lga_key(name: str) -> str:
    return re.sub(r"\s*\([^)]*\)\s*$", "", name).strip().casefold()


def read_rates(postcode_path: Path = POSTCODES, lga_path: Path = LGAS) -> tuple[dict[str, float], dict[str, float]]:
    """APVI house-PV density (fraction of suitable houses) by postcode and by Victorian LGA."""
    postcodes = {}
    with Path(postcode_path).open(newline="", encoding="utf-8-sig") as stream:
        for row in csv.DictReader(stream):
            value = row["density"].strip()
            if value:
                rate = float(value) / 100
                if not 0 < rate < 1:
                    raise ValueError(f"Invalid APVI postcode density: {row}")
                postcodes[row["postcode"].zfill(4)] = rate
    lgas = {}
    with Path(lga_path).open(newline="", encoding="utf-8-sig") as stream:
        for row in csv.DictReader(stream):
            if row["lga_state"] == "VIC" and row["density_lga"].strip():
                rate = float(row["density_lga"]) / 100
                if not 0 < rate < 1:
                    raise ValueError(f"Invalid APVI LGA density: {row}")
                key = lga_key(row["lga_name"])
                if key in lgas:
                    raise ValueError(f"Duplicate APVI LGA name: {key}")
                lgas[key] = rate
    return postcodes, lgas


def sigmoid(x: float) -> float:
    if x >= 0:
        z = math.exp(-x)
        return 1 / (1 + z)
    z = math.exp(x)
    return z / (1 + z)


def logit(p: float) -> float:
    return math.log(p / (1 - p))


def calibrated_intercept(household_agents: dict[str, int], rates: dict[str, float], target: float) -> float:
    """Common log-odds shift so that the expected agent PV share equals `target` (reported only)."""
    total = sum(household_agents.values())
    lo, hi = -40.0, 40.0
    for _ in range(90):
        mid = (lo + hi) / 2
        expected = sum(n * sigmoid(logit(rates[h]) + mid) for h, n in household_agents.items())
        if expected / total < target:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def stable_uniform(seed: int, household_id: str) -> float:
    value = hashlib.sha256(f"{seed}:{household_id}".encode()).digest()
    return (int.from_bytes(value[:8], "big") + 0.5) / 2**64


def choose_households(household_agents: dict[str, int], rates: dict[str, float], target_agents: int,
                      seed: int) -> set[str]:
    """Household-level draw with an exact agent target (common random numbers across scenarios)."""
    ranked = sorted(household_agents,
                    key=lambda h: (logit(rates[h]) - logit(stable_uniform(seed, h)), h), reverse=True)
    selected = set()
    remaining = target_agents
    for household in ranked:
        if household_agents[household] <= remaining:
            selected.add(household)
            remaining -= household_agents[household]
            if remaining == 0:
                break
    if remaining:
        raise ValueError(f"Could not reach exact house-agent PV target; remaining {remaining}")
    return selected


def household_rate(postcode: str, lga: str, postcodes: dict[str, float], lgas: dict[str, float]) -> tuple[float, str]:
    """APVI rate for a household: postcode value, else Victorian LGA value."""
    postcode = str(postcode).strip().zfill(4)
    if postcode in postcodes:
        return postcodes[postcode], "postcode"
    key = lga_key(str(lga))
    if key not in lgas:
        raise ValueError(f"No APVI postcode or Victorian LGA estimate for postcode={postcode}, lga={lga}")
    return lgas[key], "lga_fallback"
