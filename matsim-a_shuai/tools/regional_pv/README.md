# Postcode-patterned house PV (Paper 2 / Paper 3)

This folder replaces the random house PV assignment of `AdjustHousePvRatio.java` with the method used
in Paper 2. PV is assigned across postcodes using APVI rooftop-PV estimates.

- `regional_pv.py`: method functions, copied unchanged from the Paper 2 analysis script `assign_regional_pv.py`. Standard library only.
- `assign_regional_pv_paper3.py`: applies the method to the Paper 3 v2 scenario agent tables (requires pandas and numpy).
- `data/apvi_postcodes_2025.csv` and `data/apvi_lgas_2025.csv`: APVI Solar Map postcode and LGA estimates, installations up to 31 December 2025. Source: Australian PV Institute (APVI) Solar Map, https://pv-map.apvi.org.au/historical. Files are unchanged from the Paper 2 source folder:
  - SHA-256 postcodes `bfa94d0f…24ae`
  - SHA-256 LGAs `26622a33…d93d`

## Method

1. Each house household gets its APVI postcode estimate of the share of suitable houses with PV. Households whose postcode has no estimate use their Victorian LGA estimate.
2. The APVI rate gives only the spatial pattern. The total is an assumed share of house EV agents with PV: **85%**, the same in Paper 2 and Paper 3.
3. Households are ranked by `logit(rate) − logit(u)`, where `u` is a stable SHA-256 uniform draw from `seed:household`. Households are then selected until the exact agent target is reached, with seed 2025.
4. All agents of a household share one label.
5. In Paper 3, cloned agents (`<id>_c<k>`) count as separate households and keep the source household's postcode. Because the random numbers are shared, a given agent receives the same label in every scenario that contains it.

## Paper 3 usage

Run from `Simulation_paper3/input`:

```bash
python3 <repo>/matsim-a_shuai/tools/regional_pv/assign_regional_pv_paper3.py \
  --agent-tables paper3_v2/agent_tables \
  --pipeline U32=pipeline_outputs_output10_32pct_10pctsample_dwelling_first_ap15 \
  --pipeline U21=pipeline_outputs_output10_21pct_10pctsample_dwelling_first_15apa
# then rebuild the MATSim inputs (plans / ev_vehicles / chargers) for every scenario
```

Add `--check` to verify that the current agent tables already carry these labels; nothing is written in that mode.

APVI data: check the APVI terms of use before redistributing the CSV files publicly, and cite the APVI Solar Map in any publication.
