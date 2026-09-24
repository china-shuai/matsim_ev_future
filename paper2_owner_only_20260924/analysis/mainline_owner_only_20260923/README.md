# Owner-only access fix: mainline A/B rerun

## Launch status

On 23 September 2026 at approximately 19:21 Australia/Melbourne, both corrected
mainline runs were launched concurrently. The smoke test completed iterations 0–2:
1,339 positive-energy sessions across those iterations (1,202 private and 137 public),
with **zero non-owner private-charger sessions**. This is a smoke-test result, not a
claim that the full 100-iteration runs have already passed. Use `run_status.json` and
`completion.json` for current status.

## Scope

Fixes the pre-existing destination-preference substitution path that selected other
persons' private chargers. Repeats only the full-population A and B mainline runs:

- A: Apartment home access 56%; each house group 95%.
- B: Apartment home access 95%; each house group 95%.
- 35,628 EVs each; iterations 0–100; original random seed and initial SOC retained.
- Original activity schedules, time/location/destination preference inputs and
  initial-Home restriction retained. No sensitivity experiments rerun in this batch.

## Code changes

`WithinDayEvEngine` now requires explicit public status, no person ownership, and no
private ID prefix for destination AC/DCFC substitutions and the no-home DCFC substitution.
This applies both when accepting the current charger and when scanning replacements.
The existing own-home selection remains unchanged.

Immediately before the engine's sole `addVehicle` call, the person must own any
private charger. A violation throws `PRIVATE_CHARGER_ACCESS_VIOLATION` before energy
can be delivered. This invariant also protects retry, en-route and initial/overnight
entry paths. It is intentionally fail-fast rather than silently dropping bad sessions.

This patch enforces private ownership; it does not claim to redesign general charger
subscriptions or other access policies.

## Deployment and verification

The repository source has been patched. The globally installed Maven EV jar is **not**
replaced, to avoid changing other work. This batch uses `runtime/ev-owner-only.jar`,
an isolated copy of the original jar with the patched engine classes installed.
Use the recorded classpath to run this fix; unrelated launchers using the old Maven
jar are not automatically fixed.

`sources/owner_only.patch` records the source difference. `manifest.json` freezes
input, source, config, base-jar, patched-jar and dependency SHA-256 hashes. All file
inputs are copied locally, including network, fleet/SOC, population, chargers and
behavioural preferences. Inputs are checked against their originals at preparation.

`run_fixed.py test` reproduced the private-AC selection against the old jar and passed
14 deterministic checks against the patched runtime. These cover AC and DCFC candidate
selection, no-public-candidate behaviour, conflicting flags, own-home permission,
non-owner rejection, public permission, missing private-owner attributes and null candidates.
Repository JUnit entry point: `PrivateChargerAccessTest`.

`run_fixed.py smoke` runs the original 2,748-EV small population for iterations 0–2.
Launching A/B requires successful smoke completion and zero non-owner private sessions,
with both legitimate private and public charging observed.

## Running and status

Preparation and tests have distinct actions. Do not rerun `prepare` over this folder.

```
python3 analysis/mainline_owner_only_20260923/run_fixed.py test
python3 analysis/mainline_owner_only_20260923/run_fixed.py smoke
python3 analysis/mainline_owner_only_20260923/run_fixed.py launch
```

`launch` starts an independent supervisor and both full-population runs concurrently,
each with an 8-GB Java heap. It refuses existing outputs. `run_status.json` records
PIDs, commands and exit codes. Logs are in `logs/`, outputs in `outputs/`.
`completion.json` is created only after both processes exit and the successful runs'
available iteration attempt tables are ownership-audited. `passed=true` requires
both runs to complete through iteration 100 and no ownership violations.

The one initial smoke launch failed before simulation because the generated config
lacked the MATSim DOCTYPE. Its log is retained as `smoke_config_header_failure.log`.
The header-only correction and previous manifest are recorded; no input parameter
was changed by that setup correction.

Prior A/B outputs and all old figures are retained. New figures must be recomputed
after these corrected simulations finish; deleting old unauthorized rows alone is
not an equivalent correction because subsequent SOC and decisions are affected.
