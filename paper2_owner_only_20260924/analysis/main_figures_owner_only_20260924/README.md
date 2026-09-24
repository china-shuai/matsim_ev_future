# Main-text figures: owner-only corrected A/B runs

Created 24 September 2026. Replaces result Figures 3–10, not Figures 1–2 (model and study-area diagrams). The manuscript itself has not been edited. Old figures and simulations are retained.

## Source and scenarios

Only `analysis/mainline_owner_only_20260923` is used for simulation results, iteration 100. A: apartment charger access 56%, house access 95%. B: apartment access 95%, house access 95%. Both populations contain 35,628 EVs with matching IDs, dwelling types and home coordinates. No earlier baseline is mixed in. Input/source SHA256 hashes and rendering settings are in `figures/provenance.json`.

Home classification uses actual owner attributes; its equivalence to the private-charger prefix is asserted. The completed-run audit found no non-owner private charging in all 101 iterations of either run.

## Figures and definitions

| Figure | Contents | Definition |
|---|---|---|
| 3 | Baseline by dwelling | Full-session energy grouped by charging start hour; total, per fleet EV, per distinct EV starting in that hour |
| 4 | Baseline public charging map | Energy by hexagonal cell, three dwelling types |
| 5 | Baseline home charging map | Energy by hexagonal cell, three dwelling types |
| 6 | Baseline by residential zone | Same metrics as Figure 3, Inner/Middle/Outer Melbourne |
| 7 | Baseline public-charging flows | Session counts from home zone/dwelling type to charging zone; not energy |
| 8 | Policy temporal comparison | All-EV public, apartment home, apartment public power in kW |
| 9 | Policy apartment-public maps | Shared grid and shared colour scale, A versus B |
| 10 | Policy public energy by home zone | kWh per fleet EV per simulated day; only two scenario endpoints |

Original reference code is preserved under `/Users/S4065267/PyCharmMiscProject/paper2`. Layout families and metric definitions were recovered from these scripts; the outputs are refreshed versions, not pixel-identical copies.

Figures 3/6 retain the original hourly-start accounting and 06:00–22:00 display. They are NOT contemporaneous load profiles: a session's full energy belongs to its start hour. Hourly dots are shown; cubic interpolation is for display only, clipped at zero. The previous third-column wording "per session" was inaccurate for its distinct-starting-EV denominator; it is now labelled correctly. CSVs also contain the separately computed per-session means and all 24 hours.

Figure 8 allocates each session's energy uniformly over its recorded charging interval, splits at 15-minute boundaries and folds across midnight without losing energy. Power is bin energy / 0.25 h. It is an interval-average reconstruction, not a direct instantaneous event-power trace. Display smoothing: periodic Savitzky–Golay, 15 bins/order 3, clipped at zero; quantitative checks use unsmoothed bins. Both raw and smoothed series are supplied. The original kWh label is corrected to kW.

Figure 10 connects the two actual scenario endpoints for comparison; it does not estimate intermediate access levels, monotonicity or a dose-response curve. Old 30/50/70/90% policy results are not reused.

Maps use real local LGA geometry in EPSG:28355, rather than online basemap tiles. Figures 4/5 use six energy classes with shared thresholds derived from pooled dwelling-group energy cells. These are absolute-energy maps, not population-normalised demand. Figure 9 uses a shared scale capped at the pooled 98th percentile; the colour-bar arrow marks saturation. Grid extents, thresholds and cell values are exported. Quantile classes from Figure 4 and Figure 5 are not directly comparable to one another.

## Filtering and limitations

Common plotted cohort: successful, charged, positive-energy sessions with finite charging start/end and positive duration. A excludes 1 positive-energy session (55.667 kWh, 0.00878% of positive successful energy); B excludes 4 (225.125 kWh, 0.03521%). This common filter keeps maps, totals and time plots consistent, but their totals differ slightly from unfiltered audit totals. Each population has one residence without a mapped Inner/Middle/Outer zone; it remains in dwelling and all-EV figures and is excluded from zone-specific denominators. All plotted public sessions have mapped charging zones.

PNG visual checks cover all eight figures. Corrections made during QA include full y-axis limits across all plotted groups, Sankey-label spacing and endpoint-label spacing. Numerical checks independently recompute all 96 bins for all six Figure 8 series, hourly energy/denominators for Figures 3/6, totals, ownership classifications and flow conservation. Map hexbin totals are asserted during rendering. Vector files are exported from the same Matplotlib figures; separate PDF/SVG rendering and formal colour-vision simulation have not been performed.

## Files and reproduction

`figures/`: 300-dpi PNG, vector PDF and SVG, source-backed chart CSVs, daily totals and provenance. `All_figures_preview.png`: contact sheet. `Main_figures_owner_only_20260924.zip`: portable figure pack including code and chart data (not the large simulation inputs).

Run from the project environment with numpy, pandas, geopandas, matplotlib, scipy and Pillow:

```sh
MPLCONFIGDIR=/private/tmp/ev_mainfig_cache python3 analysis/main_figures_owner_only_20260924/rebuild_figures.py
python3 analysis/main_figures_owner_only_20260924/verify_and_package.py
```

This redraw does not reassess all manuscript claims or rerun sensitivity scenarios. Textual percentages and interpretations should be updated from the new outputs before submission.
