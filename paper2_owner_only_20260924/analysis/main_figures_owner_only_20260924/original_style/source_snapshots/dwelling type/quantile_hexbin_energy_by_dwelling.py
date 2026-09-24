from __future__ import annotations

from importlib import util
from pathlib import Path

import contextily as cx
import matplotlib.pyplot as plt
from matplotlib import colors
import numpy as np


HERE = Path(__file__).resolve().parent
HOTMAP2_PATH = HERE.parent / "hotmap2.py"
OUTPUT_DPI = 500
N_QUANTILES = 6


def load_hotmap2_module():
    spec = util.spec_from_file_location("paper2_hotmap2", HOTMAP2_PATH)
    module = util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def compute_quantile_boundaries(values: np.ndarray, n_quantiles: int) -> np.ndarray:
    values = np.asarray(values, dtype=float)
    values = values[np.isfinite(values) & (values > 0)]
    if values.size == 0:
        raise ValueError("No positive values available for quantile boundaries.")

    q = np.linspace(0, 1, n_quantiles + 1)
    boundaries = np.quantile(values, q)
    boundaries = np.unique(boundaries)

    if boundaries.size < 3:
        vmin = float(values.min())
        vmax = float(values.max())
        if np.isclose(vmin, vmax):
            vmax = vmin * 1.01 if vmin > 0 else 1.0
        boundaries = np.linspace(vmin, vmax, n_quantiles + 1)

    return boundaries


def collect_hexbin_cell_values(ax, sub_df, extent, gridsize):
    hb = ax.hexbin(
        sub_df["hex_x"],
        sub_df["hex_y"],
        C=sub_df["energy_kWh"],
        reduce_C_function=np.sum,
        gridsize=gridsize,
        extent=extent,
        mincnt=1,
    )
    arr = hb.get_array()
    offsets = hb.get_offsets()
    return hb, arr, offsets


def plot_quantile_maps(hm) -> None:
    link_coords = hm.parse_network_coords(hm.NETWORK_FILE)
    pop_home, lga_gdf = hm.load_population_home_zones(hm.POPULATION_FILE, hm.LGA_SHP)
    df_chargers, df_owners = hm.parse_chargers_and_owners(hm.CHARGERS_FILE)
    sess_df = hm.build_sessions(pop_home, df_chargers, df_owners, link_coords)

    sess_df["hex_x"] = np.where(sess_df["Charger_Type"] == "Private", sess_df["home_x"], sess_df["charger_x"])
    sess_df["hex_y"] = np.where(sess_df["Charger_Type"] == "Private", sess_df["home_y"], sess_df["charger_y"])

    dwellings = sorted(sess_df["dwelling_type"].dropna().unique())
    gridsize = 60
    cmap = plt.get_cmap("OrRd")

    for ctype in ["Public", "Private"]:
        data_all = sess_df[sess_df["Charger_Type"] == ctype].dropna(subset=["hex_x", "hex_y", "energy_kWh"]).copy()
        data_all = data_all[data_all["energy_kWh"] > 0]
        if data_all.empty:
            print(f"[QUANTILE] Skip {ctype}: no data.")
            continue

        cur_xmin, cur_xmax = data_all["hex_x"].min(), data_all["hex_x"].max()
        cur_ymin, cur_ymax = data_all["hex_y"].min(), data_all["hex_y"].max()
        dx = cur_xmax - cur_xmin
        dy = cur_ymax - cur_ymin
        pad_x = dx * 0.05 if dx > 0 else 1000
        pad_y = dy * 0.05 if dy > 0 else 1000
        extent = (cur_xmin - pad_x, cur_xmax + pad_x, cur_ymin - pad_y, cur_ymax + pad_y)

        tmp_fig, tmp_ax = plt.subplots()
        _, arr_all, _ = collect_hexbin_cell_values(tmp_ax, data_all, extent, gridsize)
        plt.close(tmp_fig)
        boundaries = compute_quantile_boundaries(arr_all, N_QUANTILES)
        n_bins = len(boundaries) - 1
        listed_colors = cmap(np.linspace(0.18, 0.92, n_bins))
        discrete_cmap = colors.ListedColormap(listed_colors)
        norm = colors.BoundaryNorm(boundaries, ncolors=discrete_cmap.N, clip=True)
        print(f"[QUANTILE] {ctype} boundaries: {boundaries}")

        dwellings_with_data = []
        for dw in dwellings:
            sub = sess_df[(sess_df["Charger_Type"] == ctype) & (sess_df["dwelling_type"] == dw)].copy()
            sub = sub.dropna(subset=["hex_x", "hex_y", "energy_kWh"])
            sub = sub[sub["energy_kWh"] > 0]
            if not sub.empty:
                dwellings_with_data.append(dw)

        if not dwellings_with_data:
            continue

        fig, axes = plt.subplots(
            1,
            len(dwellings_with_data),
            figsize=(2.7 * len(dwellings_with_data), 4.0),
            sharex=True,
            sharey=True,
        )
        if len(dwellings_with_data) == 1:
            axes = [axes]

        plt.subplots_adjust(left=0.03, right=0.88, top=0.90, bottom=0.20, wspace=0.01)
        cax = fig.add_axes([0.90, 0.18, 0.02, 0.64])
        hb_for_cbar = None

        for ax, dw in zip(axes, dwellings_with_data):
            sub = sess_df[(sess_df["Charger_Type"] == ctype) & (sess_df["dwelling_type"] == dw)].copy()
            sub = sub.dropna(subset=["hex_x", "hex_y", "energy_kWh"])
            sub = sub[sub["energy_kWh"] > 0]
            if sub.empty:
                ax.set_axis_off()
                continue

            xmin_p, xmax_p, ymin_p, ymax_p = extent
            ax.set_xlim(xmin_p, xmax_p)
            ax.set_ylim(ymin_p, ymax_p)

            try:
                cx.add_basemap(
                    ax,
                    source=cx.providers.CartoDB.Positron,
                    crs=hm.NETWORK_CRS,
                    attribution=False,
                    zorder=0,
                )
            except Exception as e:
                print(f"[WARN] add_basemap failed: {e}")

            hb = ax.hexbin(
                sub["hex_x"],
                sub["hex_y"],
                C=sub["energy_kWh"],
                reduce_C_function=np.sum,
                gridsize=gridsize,
                cmap=discrete_cmap,
                mincnt=1,
                extent=extent,
                norm=norm,
                alpha=0.78,
                linewidths=0.2,
                edgecolors="k",
                zorder=2,
            )
            hb_for_cbar = hb

            ax.set_aspect("equal")
            ax.set_axis_off()
            ax.text(
                0.5,
                -0.05,
                hm.format_dwelling_label(str(dw)),
                transform=ax.transAxes,
                ha="center",
                va="top",
                fontsize=13,
            )

        if hb_for_cbar is not None:
            cbar = fig.colorbar(
                hb_for_cbar,
                cax=cax,
                boundaries=boundaries,
                spacing="uniform",
            )
            mids = (boundaries[:-1] + boundaries[1:]) / 2
            tick_labels = [f"Q{i+1}" for i in range(n_bins)]
            cbar.set_ticks(mids)
            cbar.set_ticklabels(tick_labels)
            cbar.set_label("Energy quantile class", fontsize=13)
            cbar.ax.tick_params(labelsize=10)

        out_png = HERE / f"hexbin_{ctype}_energy_kWh_zoomed_quantile.png"
        out_pdf = HERE / f"hexbin_{ctype}_energy_kWh_zoomed_quantile.pdf"
        fig.savefig(out_png, dpi=OUTPUT_DPI, bbox_inches="tight")
        fig.savefig(out_pdf, bbox_inches="tight")
        plt.close(fig)
        print(f"[QUANTILE] Saved {out_png}")
        print(f"[QUANTILE] Saved {out_pdf}")


def main():
    hm = load_hotmap2_module()
    plot_quantile_maps(hm)


if __name__ == "__main__":
    main()
