# Public_demand_2scenarios_hexbin_APARTMENT_ONLY_Styled.py

import pandas as pd
import geopandas as gpd
import matplotlib.pyplot as plt
import xml.etree.ElementTree as ET
import gzip
import numpy as np
import warnings
import contextily as cx

warnings.filterwarnings(
    "ignore",
    message="Warning: 'partition' will ignore the 'mask' of the MaskedArray."
)

# =========================
# 0) PATH CONFIG
# =========================
NETWORK_CRS = "EPSG:28355"
PRIVATE_PREFIX = "private"

# 只保留 30% 和 90% 两个场景
SCENARIOS = [
    {
        "name": "Home charger access: 30% (baseline)",
        "pop": "/Users/S4065267/Matsim_ev/Simulation/100/population0.3HC.xml",
        "attempts": "/Users/S4065267/Matsim_ev/Simulation/output_0.3HC/ITERS/it.100/100.wevc_charging_attempts.csv",
        "chargers": "/Users/S4065267/Matsim_ev/Simulation/100/chargers_0.31.xml",
        "network": "/Users/S4065267/Matsim_ev/Simulation/output_0.3HC/output_network.xml.gz",
    },
    {
        "name": "Home charger access: 90%",
        "pop": "/Users/S4065267/Matsim_ev/Simulation/100/population0.9HC.xml",
        "attempts": "/Users/S4065267/Matsim_ev/Simulation/output_0.9HC/ITERS/it.100/100.wevc_charging_attempts.csv",
        "chargers": "/Users/S4065267/Matsim_ev/Simulation/100/chargers_0.91.xml",
        "network": "/Users/S4065267/Matsim_ev/Simulation/output_0.9HC/output_network.xml.gz",
    },
]

# LGA 边界底图
LGA_SHP = "/Users/S4065267/Downloads/同步空间/A_Paper1/Data/LGA/LGA.shp"
EXCLUDED_LGAS = ["Greater Geelong", "Greater Geelong (C)"]

# 输出文件名
OUT_PNG = "public_demand_2scenarios_hexbin_apartment_only_styled.png"
OUT_PDF = "public_demand_2scenarios_hexbin_apartment_only_styled.pdf"

# 【关键修改】样式完全对齐 Dwelling 代码
plt.rcParams.update({
    "font.family": "Times New Roman",
    "font.size": 16  # 改回 12
})

GRIDSIZE = 60
CLIP_Q = 0.98
ALPHA = 0.7  # 改为 0.7 (对齐 Dwelling 代码)


# =========================
# 1) Utils (保持不变)
# =========================
def parse_network_coords(xml_path: str):
    print(f"[NETWORK] Loading {xml_path} ...")
    link_coords = {}
    nodes = {}
    open_func = gzip.open if xml_path.endswith(".gz") else open
    with open_func(xml_path, "rb") as f:
        context = ET.iterparse(f, events=("end",))
        for _, elem in context:
            if elem.tag == "node":
                nid = elem.get("id")
                nodes[nid] = (float(elem.get("x")), float(elem.get("y")))
            elif elem.tag == "link":
                lid = elem.get("id")
                f_node = elem.get("from")
                t_node = elem.get("to")
                if f_node in nodes and t_node in nodes:
                    x = (nodes[f_node][0] + nodes[t_node][0]) / 2.0
                    y = (nodes[f_node][1] + nodes[t_node][1]) / 2.0
                    link_coords[lid] = (x, y)
                elem.clear()
    return link_coords

def parse_chargers(xml_path: str):
    print(f"[CHARGERS] Loading {xml_path} ...")
    open_func = gzip.open if xml_path.endswith(".gz") else open
    with open_func(xml_path, "rb") as f:
        tree = ET.parse(f)
    root = tree.getroot()
    rows = []
    for ch in root.findall("charger"):
        rows.append({"charger_id": ch.get("id"), "link_id": ch.get("link")})
    return pd.DataFrame(rows).drop_duplicates()

def load_apartment_person_ids(pop_xml: str) -> set[str]:
    print(f"[POP] Loading apartment ids from {pop_xml} ...")
    DW_ATTR_CAND = {
        "ev:dwellingType", "ev:dwellingtype", "ev:dwelling_type",
        "ev:dwelling-type", "dwellingType", "dwelling_type"
    }
    apt = set()
    open_func = gzip.open if pop_xml.endswith(".gz") else open
    with open_func(pop_xml, "rb") as f:
        context = ET.iterparse(f, events=("end",))
        for _, elem in context:
            if elem.tag != "person": continue
            pid = elem.get("id")
            dw = None
            attrs = elem.find("attributes")
            if attrs is not None:
                for a in attrs.findall("attribute"):
                    if a.get("name") in DW_ATTR_CAND:
                        dw = (a.text or "").strip(); break
            if dw == "APARTMENT": apt.add(str(pid))
            elem.clear()
    return apt

def load_public_sessions_xy_energy(attempts_csv: str, apt_ids: set[str],
                                  df_chargers: pd.DataFrame, link_coords: dict):
    print(f"[ATTEMPTS] Loading {attempts_csv} ...")
    df = pd.read_csv(attempts_csv, sep=";")
    if "successful" in df.columns: df = df[df["successful"] == True].copy()
    if "charged" in df.columns: df = df[df["charged"] == True].copy()

    df["person_id"] = df["person_id"].astype(str)
    df = df[df["person_id"].isin(apt_ids)].copy()

    df["charger_id"] = df["charger_id"].astype(str)
    df = df[~df["charger_id"].str.startswith(PRIVATE_PREFIX)].copy()

    df["energy_kWh"] = pd.to_numeric(df["energy_kWh"], errors="coerce")
    df = df[df["energy_kWh"].notna() & (df["energy_kWh"] > 0)].copy()

    df = df.merge(df_chargers, on="charger_id", how="left")
    df["link_xy"] = df["link_id"].map(link_coords)
    df["x"] = df["link_xy"].apply(lambda v: v[0] if isinstance(v, tuple) else np.nan)
    df["y"] = df["link_xy"].apply(lambda v: v[1] if isinstance(v, tuple) else np.nan)
    return df.dropna(subset=["x", "y"]).copy()[["x", "y", "energy_kWh"]]

def compute_global_extent(all_points_df: pd.DataFrame):
    gxmin, gxmax = all_points_df["x"].min(), all_points_df["x"].max()
    gymin, gymax = all_points_df["y"].min(), all_points_df["y"].max()
    gdx, gdy = gxmax - gxmin, gymax - gymin
    pad_x = 0.08 * gdx if gdx > 0 else 1000
    pad_y = 0.08 * gdy if gdy > 0 else 1000
    return (gxmin - pad_x, gxmax + pad_x, gymin - pad_y, gymax + pad_y)

def compute_shared_vmax(session_dfs: list[pd.DataFrame], extent, gridsize=60, clip_q=0.98):
    vals = []
    for df in session_dfs:
        if df.empty: continue
        fig_tmp, ax_tmp = plt.subplots()
        hb = ax_tmp.hexbin(
            df["x"], df["y"], C=df["energy_kWh"], reduce_C_function=np.sum,
            gridsize=gridsize, extent=extent, mincnt=1
        )
        arr = hb.get_array()
        plt.close(fig_tmp)
        arr = arr[arr > 0]
        if arr.size > 0: vals.append(arr)
    if not vals: return None
    all_arr = np.concatenate(vals)
    return float(np.quantile(all_arr, clip_q))

# =========================
# 【核心绘图】1x2 布局，完全对齐 Dwelling 代码样式
# =========================
def plot_1x2_shared_cbar(session_by_scenario: list[dict]):
    all_pts = pd.concat([d["df"] for d in session_by_scenario if not d["df"].empty], ignore_index=True)
    if all_pts.empty: raise ValueError("No data found.")

    extent = compute_global_extent(all_pts)
    xmin, xmax, ymin, ymax = extent

    vmax = compute_shared_vmax([d["df"] for d in session_by_scenario], extent, GRIDSIZE, CLIP_Q)
    if vmax is None or vmax <= 0: raise ValueError("No positive data.")

    # 画布大小：保持一定宽度以适应地图细节，但风格对齐
    fig, axes = plt.subplots(1, 2, figsize=(12, 6), sharex=True, sharey=True)

    # 【关键修改】布局留白（对齐 Dwelling 代码）
    plt.subplots_adjust(
        left=0.03,
        right=0.88,   # 右侧留位置给 colorbar
        top=0.90,     # Dwelling 代码是 0.90
        bottom=0.12,  # Dwelling 代码是 0.12
        wspace=0.01   # Dwelling 代码是 0.01 (紧凑)
    )

    # 【关键修改】Colorbar 位置（对齐 Dwelling 代码）
    cax = fig.add_axes([0.90, 0.18, 0.015, 0.64])

    hb_for_cbar = None

    for ax, item in zip(axes, session_by_scenario):
        df = item["df"]
        title = item["title"]

        ax.set_xlim(xmin, xmax)
        ax.set_ylim(ymin, ymax)

        # Basemap
        try:
            cx.add_basemap(ax, source=cx.providers.CartoDB.Positron, crs=NETWORK_CRS, attribution=False, zorder=0)
        except Exception as e:
            print(f"[WARN] Basemap error: {e}")

        if df.empty:
            ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center", va="center")
            ax.set_axis_off()
            continue

        # 【关键修改】Hexbin 参数（对齐 Dwelling 代码）
        hb = ax.hexbin(
            df["x"], df["y"],
            C=df["energy_kWh"],
            reduce_C_function=np.sum,
            gridsize=GRIDSIZE,
            cmap="OrRd",
            mincnt=1,
            extent=extent,
            vmin=0,
            vmax=vmax,
            alpha=0.7,          # 改为 0.7
            linewidths=0.2,     # 改为 0.2
            edgecolors="k",
            zorder=2
        )
        hb_for_cbar = hb

        ax.set_aspect("equal")
        ax.set_axis_off()

        # 【关键修改】子图标题位置和字体（对齐 Dwelling 代码）
        # Dwelling 代码：y=-0.05, fontsize=13
        ax.text(0.5, -0.05, title, transform=ax.transAxes,
                ha="center", va="top", fontsize=20)

    if hb_for_cbar is not None:
        cbar = fig.colorbar(hb_for_cbar, cax=cax)
        # 【关键修改】Colorbar 字体（对齐 Dwelling 代码）
        cbar.set_label("Public charging demand (sum of energy, kWh)", fontsize=20)
        cbar.ax.tick_params(labelsize=20)

    fig.savefig(OUT_PNG, dpi=600)
    fig.savefig(OUT_PDF)
    plt.close(fig)
    print(f"[DONE] Saved: {OUT_PNG}")

# =========================
# Main
# =========================
def main():
    session_by_scenario = []
    for sc in SCENARIOS:
        apt_ids = load_apartment_person_ids(sc["pop"])
        link_coords = parse_network_coords(sc["network"])
        df_chargers = parse_chargers(sc["chargers"])
        df_pub = load_public_sessions_xy_energy(sc["attempts"], apt_ids, df_chargers, link_coords)
        session_by_scenario.append({"title": sc["name"], "df": df_pub})

    plot_1x2_shared_cbar(session_by_scenario)

if __name__ == "__main__":
    main()