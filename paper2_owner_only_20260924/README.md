# Paper 2 corrected simulation and figure code (September 2026)

This directory is a **source-code snapshot** for the corrected owner-only charging runs and manuscript Figures 3–10. It accompanies the MATSim EV engine fix and its ownership regression tests in `contribs/ev/`.

- `analysis/mainline_owner_only_20260923/` contains the pinned full-population A/B run launcher and configuration-header helper.
- `analysis/main_figures_owner_only_20260924/` contains figure extraction, original-style rendering, LaTeX-name export, packaging, and Figure 6 diagnostic scripts.
- `analysis/main_figures_owner_only_20260924/original_style/source_snapshots/` contains verbatim copies of the author's plotting scripts used by the original-style renderer.

Simulation inputs, outputs, local GIS data, map tile caches, generated CSVs, and figure images are intentionally excluded from this code snapshot. The scripts retain their original local path assumptions and therefore require the pinned input/output files and local Python environment documented in the adjacent READMEs. The eight corrected LaTeX-ready figure PNGs are delivered separately in `Latex_ready_figures_3-10_original_names.zip` in the local analysis workspace.

The policy runs compared apartment home charger access of 56% and 95%, while house access remained 95%. Both were generated with the corrected private-charger ownership check. The initial Home activity charging exclusion was retained.
