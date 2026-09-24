import pandas as pd
import matplotlib.pyplot as plt
import matplotlib as mpl
import xml.etree.ElementTree as ET
import numpy as np
import re
from pathlib import Path
from scipy.signal import savgol_filter

# =========================
# 0. 基本配置 & 路径
# =========================
DATA_DIR = Path("/Users/S4065267/Matsim_ev/Simulation/results ana/HC")
OUTPUT_DIR = Path("/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/Figure/HC")

# 如果输出目录不存在，自动创建
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

SCENARIOS = ["0.3HC", "0.5HC", "0.7HC", "0.9HC"]  # baseline = 0.3HC
PRIVATE_PREFIX = "private"
BIN_MIN = 15

# Population 筛选条件
DWELLING_ATTR = "ev:dwellingType"
TARGET_DWELLING = "APARTMENT"

# 平滑参数
SMOOTH_METHOD = "savgol"
SAVGOL_WINDOW = 15
SAVGOL_POLY = 3

# 图片文件名
OUT_NAME = f"Combined_Load_Panel_1x3_{BIN_MIN}min_{SMOOTH_METHOD}_sci_axis"

# =========================
# 1. 绘图风格设置 (Times New Roman)
# =========================
mpl.rcParams.update({
    "font.family": "serif",
    "font.serif": ["Times New Roman"],
    "mathtext.fontset": "stix",
    "mathtext.rm": "Times New Roman",
    "mathtext.it": "Times New Roman:italic",
    "mathtext.bf": "Times New Roman:bold",

    # 字体大小适配 1x3 面板
    "font.size": 11,
    "axes.labelsize": 18,
    "xtick.labelsize": 14,
    "ytick.labelsize": 14,
    "legend.fontsize": 18,

    "lines.linewidth": 1.5,
    "savefig.bbox": "tight",
    "savefig.pad_inches": 0.05,
})


# =========================
# 2. 工具函数
# =========================
def find_one_file(dir_path: Path, patterns: list[str]) -> Path | None:
    for pat in patterns:
        hits = sorted(dir_path.glob(pat))
        if hits:
            return hits[0]
    return None


def legend_label_from_scenario(sc: str) -> str:
    m = re.search(r"(0\.[0-9]+)", sc)
    if not m:
        return sc
    rate = float(m.group(1))
    pct = int(round(rate * 100))
    # 【修改点2】文案修改为 Home charger access
    lab = f"Home charger access: {pct}%"
    if abs(rate - 0.3) < 1e-12:
        lab += " (baseline)"
    return lab


def pick_time_cols(df: pd.DataFrame) -> tuple[str, str]:
    if "charging_start_time" in df.columns and "charging_end_time" in df.columns:
        return "charging_start_time", "charging_end_time"
    return "start_time", "end_time"


def smooth_series(y: np.ndarray) -> np.ndarray:
    """ 对曲线进行平滑处理 """
    y = np.asarray(y, dtype=float)
    if SMOOTH_METHOD == "none" or len(y) < 7:
        return y
    w = int(SAVGOL_WINDOW)
    if w % 2 == 0: w += 1
    if w >= len(y): w = len(y) if (len(y) % 2 == 1) else (len(y) - 1)
    if w < SAVGOL_POLY + 2: return y
    return savgol_filter(y, window_length=w, polyorder=SAVGOL_POLY, mode="interp")


def load_apartment_person_set(pop_xml: Path) -> set[str]:
    """ 从 XML 中提取住在 Apartment 的 person_id """
    apt = set()
    for event, elem in ET.iterparse(pop_xml, events=("end",)):
        if elem.tag == "person":
            pid = elem.get("id")
            dw = None
            attrs = elem.find("attributes")
            if attrs is not None:
                for a in attrs.findall("attribute"):
                    if a.get("name") == DWELLING_ATTR:
                        dw = (a.text or "").strip()
                        break
            if dw == TARGET_DWELLING:
                apt.add(str(pid))
            elem.clear()
    return apt


def build_load_timeseries_from_df(df: pd.DataFrame, bin_min: int) -> pd.Series:
    """ 将充电记录转换为时间序列负荷 (kW) """
    if df.empty:
        return pd.Series(dtype=float)

    # 基础清洗
    if "successful" in df.columns:
        df = df[df["successful"].astype(bool)]
    df = df[df["energy_kWh"].notna() & (df["energy_kWh"] > 0)]

    if df.empty: return pd.Series(dtype=float)

    s_col, e_col = pick_time_cols(df)
    s = pd.to_numeric(df[s_col], errors="coerce").to_numpy()
    e = pd.to_numeric(df[e_col], errors="coerce").to_numpy()
    energy = pd.to_numeric(df["energy_kWh"], errors="coerce").to_numpy()

    # 过滤无效数据
    m = np.isfinite(s) & np.isfinite(e) & np.isfinite(energy) & (energy > 0) & (e > s)
    s, e, energy = s[m], e[m], energy[m]
    if len(s) == 0: return pd.Series(dtype=float)

    day = 24 * 3600
    s = np.mod(s, day)
    e = np.mod(e, day)
    crosses = e < s

    bin_s = bin_min * 60
    nbins = int(np.ceil(day / bin_s))
    bin_edges = np.arange(0, nbins * bin_s + 1, bin_s, dtype=float)
    energy_in_bin = np.zeros(nbins, dtype=float)

    # 累加能量
    def add_sessions(s_arr, e_arr, en_arr, dur_arr):
        for si, ei, en, d in zip(s_arr, e_arr, en_arr, dur_arr):
            p = en * 3600.0 / d  # Power (kW)
            b0 = int(np.floor(si / bin_s))
            b1 = int(np.floor((ei - 1e-9) / bin_s))
            for b in range(b0, b1 + 1):
                left = max(si, bin_edges[b])
                right = min(ei, bin_edges[b + 1])
                overlap = max(0.0, right - left)
                if overlap > 0:
                    energy_in_bin[b] += p * (overlap / 3600.0)  # kWh

    dur = e - s
    add_sessions(s[~crosses], e[~crosses], energy[~crosses], dur[~crosses])

    # 处理跨天
    if np.any(crosses):
        s2, e2, en2, d2 = s[crosses], e[crosses], energy[crosses], dur[crosses]
        durA = (day - s2)
        add_sessions(s2, np.full_like(s2, day, dtype=float), en2 * (durA / d2), durA)
        durB = e2
        add_sessions(np.zeros_like(e2, dtype=float), e2, en2 * (durB / d2), durB)

    # 转换为功率 (kW) = kWh / hours
    load_kw = energy_in_bin / (bin_s / 3600.0)
    t_hours = (bin_edges[:-1] + bin_s / 2) / 3600.0
    return pd.Series(load_kw, index=t_hours)


# =========================
# 3. 数据加载主循环
# =========================
data_store = {}

print(">>> Starting Data Loading...")

for sc in SCENARIOS:
    print(f"--- Processing Scenario: {sc} ---")

    attempts_path = find_one_file(DATA_DIR, [
        f"*wevc_charging_attempts*{sc}*.csv",
        f"*wevc_charging_attempts*{sc.replace('HC', 'CH')}*.csv",
        f"*wevc_charging_attempts*{sc.replace('CH', 'HC')}*.csv",
    ])
    pop_path = find_one_file(DATA_DIR, [
        f"population*{sc}*.xml",
        f"population*{sc.replace('HC', 'CH')}*.xml",
        f"population*{sc.replace('CH', 'HC')}*.xml",
    ])

    if not attempts_path or not pop_path:
        print(f"[SKIP] Missing files for {sc}")
        continue

    # (A) Apartment IDs
    apt_ids = load_apartment_person_set(pop_path)

    # (B) Attempts DataFrame
    df = pd.read_csv(attempts_path, sep=";")
    df["person_id"] = df["person_id"].astype(str)

    # (C) Series Construction
    is_private = df["charger_id"].astype(str).str.startswith(PRIVATE_PREFIX)
    df_total_public = df[~is_private]

    df_apt = df[df["person_id"].isin(apt_ids)]
    df_apt_home = df_apt[df_apt["charger_id"].astype(str).str.startswith(PRIVATE_PREFIX)]
    df_apt_public = df_apt[~df_apt["charger_id"].astype(str).str.startswith(PRIVATE_PREFIX)]

    data_store[sc] = {
        'total_public': build_load_timeseries_from_df(df_total_public, BIN_MIN),
        'apt_home': build_load_timeseries_from_df(df_apt_home, BIN_MIN),
        'apt_public': build_load_timeseries_from_df(df_apt_public, BIN_MIN)
    }

if not data_store:
    raise RuntimeError("No data loaded!")

# =========================
# 4. 绘图 (1x3 Panel)
# =========================
print(">>> Plotting...")

fig, axes = plt.subplots(1, 3, figsize=(12, 4.5))

subplots_config = [
    {
        'key': 'total_public',
        'label': '(a) Total Public Charging Demand',
        'ylabel': 'Public Energy Demand (kWh)'
    },
    {
        'key': 'apt_home',
        'label': '(b) Apartment Home Charging Demand',
        'ylabel': 'Home Energy Demand (kWh)'
    },
    {
        'key': 'apt_public',
        'label': '(c) Apartment Public Charging Demand',
        'ylabel': 'Public Energy Demand (kWh)'
    }
]

for i, ax in enumerate(axes):
    config = subplots_config[i]
    data_key = config['key']

    for sc in SCENARIOS:
        if sc not in data_store: continue

        series = data_store[sc][data_key]
        if series.empty: continue

        x = series.index.to_numpy()
        y = smooth_series(series.values)

        lw = 1.5
        label = legend_label_from_scenario(sc)

        ax.plot(x, y, linewidth=lw, label=label)

    ax.set_xlim(6, 22)
    ax.set_xticks(range(6, 23, 4))
    ax.set_xlabel("Hour of day", labelpad=8)
    ax.set_ylabel(config['ylabel'])
    ax.grid(True, alpha=0.25)

    # 【修改点1】开启科学计数法
    ax.ticklabel_format(axis='y', style='sci', scilimits=(0, 0), useMathText=True)
    ax.yaxis.get_offset_text().set_fontsize(11)  # 调整 10^x 的字体大小

# 先留出版式空间，再用 figure-level 文本放子标题，避免与 x 轴标题和图例互相挤压
plt.subplots_adjust(top=0.95, bottom=0.32, left=0.06, right=0.98, wspace=0.30)

for ax, config in zip(axes, subplots_config):
    pos = ax.get_position()
    x_center = 0.5 * (pos.x0 + pos.x1)
    fig.text(
        x_center,
        0.165,
        config['label'],
        ha='center',
        va='top',
        fontsize=18,
        fontweight='normal'
    )

# 【修改点3】图例改为两行 (ncol=2)
handles, labels = axes[0].get_legend_handles_labels()
fig.legend(
    handles, labels,
    loc="lower center",
    bbox_to_anchor=(0.5, -0.10),
    ncol=2,  # 两行
    frameon=False,
    columnspacing=1.5,
    handlelength=2.0,
    prop={"size": 18}
)

out_png = OUTPUT_DIR / f"{OUT_NAME}.png"
out_pdf = OUTPUT_DIR / f"{OUT_NAME}.pdf"

fig.savefig(out_png, dpi=600)
fig.savefig(out_pdf)

plt.close()
print(f"[DONE] Figures saved to:\n  {out_png}\n  {out_pdf}")
