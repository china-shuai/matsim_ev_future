#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path

import geopandas as gpd
import matplotlib
import numpy as np
import pandas as pd

matplotlib.use("Agg")
import matplotlib.pyplot as plt


DATA_DIR = Path("/Users/S4065267/Matsim_ev/Simulation/results ana/HC")
LGA_SHP = Path("/Users/S4065267/Downloads/同步空间/A_Paper1/Data/LGA/LGA.shp")
NETWORK_CRS = "EPSG:28355"
SCENARIOS = ["0.3HC", "0.5HC", "0.7HC", "0.9HC"]
PRIVATE_PREFIX = "private"
ZONE_ORDER = ["Inner", "Middle", "Outer"]
DWELLING_ATTR_NAMES = ["ev:dwellingType", "ev:dwellingtype", "dwellingType"]
TARGET_DWELLING = "APARTMENT"

LGA_TO_ZONE = {
    "Melbourne": "Inner",
    "Yarra": "Inner",
    "Port Phillip": "Inner",
    "Stonnington": "Middle",
    "Maribyrnong": "Middle",
    "Moonee Valley": "Middle",
    "Moreland": "Middle",
    "Merri-bek": "Middle",
    "Darebin": "Middle",
    "Banyule": "Middle",
    "Boroondara": "Middle",
    "Whitehorse": "Middle",
    "Monash": "Middle",
    "Glen Eira": "Middle",
    "Bayside": "Middle",
    "Hobsons Bay": "Middle",
    "Kingston": "Middle",
    "Manningham": "Middle",
    "Brimbank": "Outer",
    "Hume": "Outer",
    "Whittlesea": "Outer",
    "Nillumbik": "Outer",
    "Maroondah": "Outer",
    "Knox": "Outer",
    "Yarra Ranges": "Outer",
    "Greater Dandenong": "Outer",
    "Casey": "Outer",
    "Cardinia": "Outer",
    "Frankston": "Outer",
    "Mornington Peninsula": "Outer",
    "Wyndham": "Outer",
    "Melton": "Outer",
    "Mitchell": "Outer",
    "Macedon Ranges": "Outer",
    "Moorabool": "Outer",
    "Murrindindi": "Outer",
}
EXCLUDED_LGAS = {"Greater Geelong", "Greater Geelong (C)"}
ZONE_COLORS = {
    "Inner": "#d62728",
    "Middle": "#1f77b4",
    "Outer": "#2ca02c",
}
ZONE_LABELS = {
    "Inner": "Inner Melbourne",
    "Middle": "Middle Melbourne",
    "Outer": "Outer Melbourne",
}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Plot change in public charging energy across apartment home-charger "
            "access scenarios, grouped by residential zone."
        )
    )
    parser.add_argument("--data-dir", type=Path, default=DATA_DIR)
    parser.add_argument("--lga-shp", type=Path, default=LGA_SHP)
    parser.add_argument(
        "--denominator",
        choices=["fleet_ev", "charging_ev"],
        default="fleet_ev",
        help="fleet_ev = kWh per EV in fleet; charging_ev = kWh per charging EV.",
    )
    parser.add_argument(
        "--make-reduction-plot",
        action="store_true",
        help="Also generate reduction ratio relative to 30%% baseline.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("/Users/S4065267/PyCharmMiscProject/paper2/publicvspri"),
    )
    return parser


def normalize_lga_name(name: str | None) -> str | None:
    if name is None or pd.isna(name):
        return name
    return str(name).replace("(C)", "").replace("(Vic.)", "").strip()


def scenario_label(scenario: str) -> str:
    match = re.search(r"(0\.[0-9]+)", scenario)
    if not match:
        return scenario
    return f"{int(round(float(match.group(1)) * 100))}%"


def find_one_file(dir_path: Path, patterns: list[str]) -> Path | None:
    for pattern in patterns:
        hits = sorted(dir_path.glob(pattern))
        if hits:
            return hits[0]
    return None


def find_scenario_inputs(data_dir: Path, scenario: str) -> tuple[Path, Path]:
    attempts_path = find_one_file(
        data_dir,
        [
            f"*wevc_charging_attempts*{scenario}*.csv",
            f"*wevc_charging_attempts*{scenario.replace('HC', 'CH')}*.csv",
            f"*wevc_charging_attempts*{scenario.replace('CH', 'HC')}*.csv",
        ],
    )
    population_path = find_one_file(
        data_dir,
        [
            f"population*{scenario}*.xml",
            f"population*{scenario.replace('HC', 'CH')}*.xml",
            f"population*{scenario.replace('CH', 'HC')}*.xml",
        ],
    )
    if attempts_path is None or population_path is None:
        raise FileNotFoundError(
            f"Missing files for {scenario}. attempts={attempts_path}, population={population_path}"
        )
    return attempts_path, population_path


def load_population_records(pop_xml: Path) -> pd.DataFrame:
    records: list[dict[str, object]] = []
    for _, elem in ET.iterparse(pop_xml, events=("end",)):
        if elem.tag != "person":
            continue

        person_id = elem.get("id")
        dwelling_type = None
        attrs = elem.find("attributes")
        if attrs is not None:
            for attr in attrs.findall("attribute"):
                if attr.get("name") in DWELLING_ATTR_NAMES:
                    dwelling_type = (attr.text or "").strip()
                    break

        plan = elem.find("plan")
        home_x = None
        home_y = None
        if plan is not None:
            for act in plan.findall("activity"):
                if act.get("type") == "Home":
                    home_x = act.get("x")
                    home_y = act.get("y")
                    break
            if home_x is None or home_y is None:
                first_act = plan.find("activity")
                if first_act is not None:
                    home_x = first_act.get("x")
                    home_y = first_act.get("y")

        if person_id and home_x is not None and home_y is not None:
            records.append(
                {
                    "person_id": str(person_id),
                    "home_x": float(home_x),
                    "home_y": float(home_y),
                    "dwelling_type": dwelling_type,
                }
            )
        elem.clear()

    return pd.DataFrame(records)


def attach_zone(pop_df: pd.DataFrame, lga_shp: Path) -> pd.DataFrame:
    if pop_df.empty:
        return pd.DataFrame(columns=["person_id", "zone", "dwelling_type"])

    pop_gdf = gpd.GeoDataFrame(
        pop_df.copy(),
        geometry=gpd.points_from_xy(pop_df["home_x"], pop_df["home_y"]),
        crs=NETWORK_CRS,
    )
    lga_gdf = gpd.read_file(lga_shp).to_crs(NETWORK_CRS)
    if "lga_name" not in lga_gdf.columns:
        raise ValueError("LGA shapefile does not contain 'lga_name'")

    lga_gdf["homeLGA"] = lga_gdf["lga_name"].apply(normalize_lga_name)
    try:
        joined = gpd.sjoin(
            pop_gdf,
            lga_gdf[["homeLGA", "geometry"]],
            how="left",
            predicate="within",
        )
    except TypeError:
        joined = gpd.sjoin(
            pop_gdf,
            lga_gdf[["homeLGA", "geometry"]],
            how="left",
            op="within",
        )

    joined["homeLGA"] = joined["homeLGA"].apply(normalize_lga_name)
    joined["zone"] = joined["homeLGA"].map(LGA_TO_ZONE)
    joined = joined[
        joined["zone"].isin(ZONE_ORDER) & ~joined["homeLGA"].isin(EXCLUDED_LGAS)
    ].copy()
    return joined[["person_id", "zone", "dwelling_type"]].drop_duplicates()


def aggregate_scenario(
    scenario: str,
    attempts_path: Path,
    pop_zone_df: pd.DataFrame,
    denominator: str,
    scope: str,
) -> pd.DataFrame:
    attempts = pd.read_csv(attempts_path, sep=";")
    attempts["person_id"] = attempts["person_id"].astype(str)

    if "successful" in attempts.columns:
        attempts = attempts[attempts["successful"].astype(bool)]
    if "charged" in attempts.columns:
        attempts = attempts[attempts["charged"].astype(bool)]
    attempts = attempts[attempts["energy_kWh"].notna() & (attempts["energy_kWh"] > 0)].copy()

    pop_zone_df = pop_zone_df.copy()
    pop_zone_df["person_id"] = pop_zone_df["person_id"].astype(str)
    if scope == "apartment":
        pop_zone_df = pop_zone_df[pop_zone_df["dwelling_type"] == TARGET_DWELLING].copy()

    merged = attempts.merge(pop_zone_df, on="person_id", how="inner")
    merged = merged[merged["zone"].isin(ZONE_ORDER)].copy()
    merged["is_public"] = ~merged["charger_id"].astype(str).str.startswith(PRIVATE_PREFIX, na=False)

    if scope == "apartment":
        denominator_source = pop_zone_df
    else:
        denominator_source = pop_zone_df

    if denominator == "fleet_ev":
        denom = (
            denominator_source.groupby("zone", as_index=False)["person_id"]
            .nunique()
            .rename(columns={"person_id": "denominator"})
        )
    else:
        denom = (
            merged[merged["is_public"]]
            .groupby("zone", as_index=False)["person_id"]
            .nunique()
            .rename(columns={"person_id": "denominator"})
        )

    numer = (
        merged[merged["is_public"]]
        .groupby("zone", as_index=False)["energy_kWh"]
        .sum()
        .rename(columns={"energy_kWh": "public_energy_kWh"})
    )

    summary = pd.DataFrame({"zone": ZONE_ORDER})
    summary["scenario"] = scenario
    summary = summary.merge(numer, on="zone", how="left").merge(denom, on="zone", how="left")
    summary["public_energy_kWh"] = summary["public_energy_kWh"].fillna(0.0)
    summary["denominator"] = summary["denominator"].fillna(0.0)
    summary["public_energy_per_ev_kWh"] = np.where(
        summary["denominator"] > 0,
        summary["public_energy_kWh"] / summary["denominator"],
        np.nan,
    )
    return summary


def compute_all_scenarios(
    data_dir: Path,
    lga_shp: Path,
    denominator: str,
    scope: str,
) -> pd.DataFrame:
    frames: list[pd.DataFrame] = []
    population_cache: dict[Path, pd.DataFrame] = {}

    for scenario in SCENARIOS:
        attempts_path, population_path = find_scenario_inputs(data_dir, scenario)
        if population_path not in population_cache:
            pop_df = load_population_records(population_path)
            population_cache[population_path] = attach_zone(pop_df, lga_shp)
        scenario_df = aggregate_scenario(
            scenario=scenario,
            attempts_path=attempts_path,
            pop_zone_df=population_cache[population_path],
            denominator=denominator,
            scope=scope,
        )
        frames.append(scenario_df)

    result = pd.concat(frames, ignore_index=True)
    result["scenario_pct"] = result["scenario"].map(lambda s: int(round(float(re.search(r"0\.[0-9]+", s).group()) * 100)))

    baseline = (
        result[result["scenario"] == "0.3HC"][["zone", "public_energy_per_ev_kWh"]]
        .rename(columns={"public_energy_per_ev_kWh": "baseline_public_energy_per_ev_kWh"})
    )
    result = result.merge(baseline, on="zone", how="left")
    result["reduction_ratio"] = np.where(
        result["baseline_public_energy_per_ev_kWh"] > 0,
        (result["baseline_public_energy_per_ev_kWh"] - result["public_energy_per_ev_kWh"])
        / result["baseline_public_energy_per_ev_kWh"],
        np.nan,
    )
    return result.sort_values(["zone", "scenario_pct"]).reset_index(drop=True)


def apply_plot_style() -> None:
    plt.rcParams.update(
        {
            "font.family": "Times New Roman",
            "font.size": 14,
            "axes.labelsize": 15,
            "xtick.labelsize": 13,
            "ytick.labelsize": 13,
            "legend.fontsize": 13,
            "axes.linewidth": 1.0,
            "savefig.bbox": "tight",
            "savefig.pad_inches": 0.03,
        }
    )


def plot_metric_subplot(
    ax: plt.Axes,
    df: pd.DataFrame,
    value_col: str,
    subplot_title: str | None = None,
    as_percent: bool = False,
) -> None:
    x_values = [scenario_label(sc) for sc in SCENARIOS]

    for zone in ZONE_ORDER:
        zone_df = df[df["zone"] == zone].copy()
        y = zone_df[value_col].to_numpy(dtype=float)
        if as_percent:
            y = y * 100.0
        ax.plot(
            x_values,
            y,
            marker="o",
            markersize=6,
            linewidth=2.0,
            color=ZONE_COLORS[zone],
            label=ZONE_LABELS[zone],
        )

    ax.grid(True, axis="y", linestyle="--", alpha=0.35)
    if subplot_title:
        ax.text(0.5, -0.20, subplot_title, transform=ax.transAxes, ha="center", va="top")


def plot_metric_single(
    df: pd.DataFrame,
    value_col: str,
    ylabel: str,
    output_path: Path,
    as_percent: bool = False,
) -> None:
    apply_plot_style()
    fig, ax = plt.subplots(figsize=(5.4, 4.2))

    plot_metric_subplot(
        ax=ax,
        df=df,
        value_col=value_col,
        subplot_title=None,
        as_percent=as_percent,
    )

    ax.set_xlabel("Home-charger access scenario")
    ax.set_ylabel(ylabel)
    ax.yaxis.set_label_coords(-0.10, 0.45)

    handles, labels = ax.get_legend_handles_labels()
    fig.legend(
        handles,
        labels,
        loc="lower center",
        ncol=3,
        frameon=False,
        bbox_to_anchor=(0.5, -0.02),
    )
    fig.subplots_adjust(left=0.16, right=0.98, bottom=0.18)
    fig.savefig(output_path.with_suffix(".png"), dpi=600)
    fig.savefig(output_path.with_suffix(".pdf"))
    plt.close(fig)


def main() -> None:
    args = build_parser().parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    overall_result = compute_all_scenarios(
        data_dir=args.data_dir,
        lga_shp=args.lga_shp,
        denominator=args.denominator,
        scope="all",
    )
    apartment_result = compute_all_scenarios(
        data_dir=args.data_dir,
        lga_shp=args.lga_shp,
        denominator=args.denominator,
        scope="apartment",
    )

    denom_tag = "per_fleet_ev" if args.denominator == "fleet_ev" else "per_charging_ev"
    overall_stem = f"public_energy_change_by_zone_all_residents_{denom_tag}"
    apartment_stem = f"public_energy_change_by_zone_apartment_{denom_tag}"
    combined_stem = f"public_energy_change_by_zone_combined_{denom_tag}"

    overall_csv_path = args.output_dir / f"{overall_stem}.csv"
    apartment_csv_path = args.output_dir / f"{apartment_stem}.csv"
    overall_result.to_csv(overall_csv_path, index=False)
    apartment_result.to_csv(apartment_csv_path, index=False)

    if args.denominator == "fleet_ev":
        ylabel = "Public charging energy\nper EV (kWh)"
    else:
        ylabel = "Public charging energy\nper charging EV (kWh)"

    plot_metric_single(
        df=overall_result,
        value_col="public_energy_per_ev_kWh",
        ylabel=ylabel,
        output_path=args.output_dir / overall_stem,
    )

    if args.make_reduction_plot:
        plot_metric_single(
            df=overall_result,
            value_col="reduction_ratio",
            ylabel="Reduction vs 30% baseline (%)",
            output_path=args.output_dir / f"{overall_stem}_reduction",
            as_percent=True,
        )

    print(f"Saved summary CSVs: {overall_csv_path} and {apartment_csv_path}")
    print(f"Saved figure(s) under: {args.output_dir}")


if __name__ == "__main__":
    main()
