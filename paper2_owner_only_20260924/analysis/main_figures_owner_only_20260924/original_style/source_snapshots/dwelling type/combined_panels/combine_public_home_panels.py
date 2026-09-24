from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

import matplotlib.pyplot as plt


BASE_DIR = Path(__file__).resolve().parents[1]
SOURCE_SCRIPT = BASE_DIR / "figure 1.py"
OUTPUT_STEM = Path(__file__).resolve().parent / "Figure_Dwelling_Public_Home_Combined"


def load_source_module():
    spec = spec_from_file_location("dwelling_panel_source", SOURCE_SCRIPT)
    module = module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def add_panel_label(ax, label):
    ax.text(
        0.5,
        -0.28,
        label,
        transform=ax.transAxes,
        ha="center",
        va="top",
        fontsize=18,
        fontweight="normal",
    )


def plot_combined_2x3(source_module, public_metrics, home_metrics, outfile_stem):
    fig, axes = plt.subplots(2, 3, figsize=(12, 9))

    metric_specs = [
        ("Total energy demand", True),
        ("Energy per fleet EV", False),
        ("Energy per session", False),
    ]
    row_specs = [
        ("Public", public_metrics),
        ("Home", home_metrics),
    ]
    panel_letters = iter("abcdef")

    for row_idx, (charging_type, metrics) in enumerate(row_specs):
        for col_idx, ((metric_title, use_sci), pivot_data) in enumerate(zip(metric_specs, metrics)):
            ax = axes[row_idx, col_idx]
            if col_idx == 0:
                ylabel = f"{charging_type} Energy Demand (kWh)"
            elif col_idx == 1:
                ylabel = f"{charging_type} (kWh per EV in fleet)"
            else:
                ylabel = f"{charging_type} (kWh per session)"

            source_module.plot_single_axis(ax, pivot_data, ylabel, use_sci=use_sci)
            panel_label = f"({next(panel_letters)}) {metric_title}"
            add_panel_label(ax, panel_label)

    handles, labels = axes[0, 0].get_legend_handles_labels()
    fig.legend(
        handles,
        labels,
        loc="lower center",
        bbox_to_anchor=(0.5, 0.015),
        ncol=len(labels),
        frameon=False,
        columnspacing=1.5,
    )

    fig.subplots_adjust(
        top=0.95,
        bottom=0.19,
        left=0.08,
        right=0.98,
        wspace=0.30,
        hspace=0.52,
    )

    png_path = f"{outfile_stem}.png"
    pdf_path = f"{outfile_stem}.pdf"
    fig.savefig(png_path, dpi=500, bbox_inches="tight")
    fig.savefig(pdf_path, bbox_inches="tight")
    plt.close(fig)
    print(f"[PLOT] Saved combined figure: {png_path}")
    print(f"[PLOT] Saved combined figure: {pdf_path}")


def main():
    source_module = load_source_module()

    person_dw = source_module.load_person_dwelling(source_module.POPULATION_FILE)
    ev_counts_dict = source_module.compute_ev_counts_by_dwelling(person_dw)
    df_chargers, df_owners = source_module.parse_chargers_and_owners(source_module.CHARGERS_FILE)
    sess_df = source_module.build_sessions_with_dwelling(person_dw, df_chargers, df_owners)

    public_metrics = source_module.prepare_metrics(sess_df, ev_counts_dict, "Public")
    home_metrics = source_module.prepare_metrics(sess_df, ev_counts_dict, "Private")

    if public_metrics[0] is None or home_metrics[0] is None:
        raise RuntimeError("Missing public or home metrics; combined panel was not generated.")

    plot_combined_2x3(source_module, public_metrics, home_metrics, OUTPUT_STEM)


if __name__ == "__main__":
    main()
