import pandas as pd
import matplotlib.pyplot as plt
import xml.etree.ElementTree as ET
import gzip
import re
import numpy as np
from scipy.interpolate import make_interp_spline

# ============== 0. 基本配置 ==============
POPULATION_FILE = '/Users/S4065267/Matsim_ev/Simulation/100/population0.3HC.xml'
CHARGERS_FILE = '/Users/S4065267/Matsim_ev/Simulation/100/chargers_0.31.xml'
ATTEMPTS_CSV = '/Users/S4065267/Matsim_ev/Simulation/output_0.3HC/ITERS/it.100/100.wevc_charging_attempts.csv'

# 【配置】字体设置 (保留您提供的大字号设置)
plt.rcParams.update({
    'font.family': 'Times New Roman',
    'font.size': 11,
    'axes.labelsize': 18,  # 18号大字体
    'xtick.labelsize': 14,
    'ytick.labelsize': 14,
    'legend.fontsize': 18  # 18号大字体
})

# 图例名称映射
DWELLING_LABEL_MAP = {
    'APARTMENT': 'Apartment',
    'HOUSE_NO_PV': 'House without PV',
    'HOUSE_WITH_PV': 'House with PV',
}


# ============== 1. 数据加载与处理部分 (保持不变) ==============
def load_person_dwelling(pop_xml_path):
    print(f"[POP] Loading {pop_xml_path} ...")
    tree = ET.parse(pop_xml_path)
    root = tree.getroot()
    records = []
    DW_ATTR_CANDIDATES = [
        'ev:dwellingType', 'ev:dwellingtype', 'ev:dwelling_type',
        'ev:dwelling-type', 'dwellingType', 'dwelling_type'
    ]
    for person in root.findall('person'):
        pid = person.get('id')
        dw_type = None
        attrs = person.find('attributes')
        if attrs is not None:
            for a in attrs.findall('attribute'):
                name = a.get('name')
                if name in DW_ATTR_CANDIDATES:
                    dw_type = (a.text or '').strip()
                    break
        records.append({'person_id': pid, 'dwelling_type': dw_type})
    df = pd.DataFrame(records).drop_duplicates(subset=['person_id'])
    print(f"[POP] persons with dwelling_type: {len(df)}")
    return df


def compute_ev_counts_by_dwelling(person_dw: pd.DataFrame):
    df = person_dw[~person_dw['dwelling_type'].isna()].copy()
    counts = df.groupby('dwelling_type')['person_id'].nunique().to_dict()
    print("[POP] EV counts by dwelling_type:", counts)
    return counts


def parse_chargers_and_owners(xml_path):
    print(f"[CHARGERS] Loading {xml_path} ...")
    open_func = gzip.open if xml_path.endswith('.gz') else open
    with open_func(xml_path, 'rb') as f:
        tree = ET.parse(f)
    root = tree.getroot()
    chargers = []
    owners = []
    for ch in root.findall('charger'):
        cid = ch.get('id')
        link = ch.get('link')
        chargers.append({'charger_id': cid, 'link_id': link})
        attrs = ch.find('attributes')
        persons_str = None
        if attrs is not None:
            for a in attrs.findall('attribute'):
                if a.get('name') == 'sevc:persons':
                    persons_str = (a.text or '').strip()
                    break
        if persons_str:
            person_ids = re.split(r'[,\s]+', persons_str)
            person_ids = [p for p in person_ids if p != '']
            for pid in person_ids:
                owners.append({'charger_id': cid, 'owner_person_id': pid})
    df_chargers = pd.DataFrame(chargers).drop_duplicates()
    df_owners = pd.DataFrame(owners)
    return df_chargers, df_owners


def build_sessions_with_dwelling(person_dw, df_chargers, df_owners):
    print(f"[ATTEMPTS] Loading {ATTEMPTS_CSV} ...")
    df_a = pd.read_csv(ATTEMPTS_CSV, sep=';')
    df_a = df_a[(df_a['successful'] == True) & (df_a['charged'] == True)].copy()
    df_a['person_id'] = df_a['person_id'].astype(str)
    person_dw = person_dw.copy()
    person_dw['person_id'] = person_dw['person_id'].astype(str)
    df = df_a.merge(person_dw, on='person_id', how='left')
    df = df.merge(df_chargers[['charger_id']], on='charger_id', how='left')
    if not df_owners.empty:
        df_owners = df_owners.copy()
        df_owners['owner_person_id'] = df_owners['owner_person_id'].astype(str)
        df = df.merge(
            df_owners[['charger_id', 'owner_person_id']],
            left_on=['charger_id', 'person_id'],
            right_on=['charger_id', 'owner_person_id'],
            how='left'
        )
        df['Charger_Type'] = df['owner_person_id'].notna().map(
            lambda x: 'Private' if x else 'Public'
        )
    else:
        df['Charger_Type'] = 'Public'
    print("[SESS] Charger_Type counts:")
    print(df['Charger_Type'].value_counts(dropna=False))
    return df


def _aggregate_hourly_energy(df_in: pd.DataFrame):
    df = df_in.copy()
    df = df[~df['dwelling_type'].isna()].copy()
    if df.empty: return None, None, None
    df['hour'] = ((df['charging_start_time'] % 86400) // 3600).astype(int)
    agg = df.groupby(['hour', 'dwelling_type'], as_index=False).agg(
        total_energy_kWh=('energy_kWh', 'sum'),
        n_sessions=('person_id', 'size'),
        n_cars=('person_id', 'nunique')
    )
    hours_range = pd.Index(range(6, 23), name='hour')

    def pivot_and_reindex(val_col):
        p = agg.pivot(index='hour', columns='dwelling_type', values=val_col).fillna(0.0)
        return p.reindex(hours_range, fill_value=0.0)

    pivot_energy = pivot_and_reindex('total_energy_kWh')
    pivot_n_sessions = pivot_and_reindex('n_sessions')
    pivot_n_cars = pivot_and_reindex('n_cars')
    return pivot_energy, pivot_n_sessions, pivot_n_cars


# ============== 2. 核心绘图逻辑 ==============

def _plot_spline_or_line(ax, x_orig, y_orig, label):
    x_orig = np.asarray(x_orig, dtype=float)
    y_orig = np.asarray(y_orig, dtype=float)
    if np.count_nonzero(y_orig) <= 3:
        ax.plot(x_orig, y_orig, linewidth=2, linestyle='--', label=label)
        return
    x_smooth = np.linspace(x_orig.min(), x_orig.max(), 400)
    try:
        spline = make_interp_spline(x_orig, y_orig, k=3)
        y_smooth = np.maximum(spline(x_smooth), 0)
        ax.plot(x_smooth, y_smooth, linewidth=2, label=label)
    except Exception:
        ax.plot(x_orig, y_orig, linewidth=2, linestyle='--', label=label)


def plot_single_axis(ax, pivot_data, ylabel, use_sci=False):
    if pivot_data is None or pivot_data.empty:
        ax.text(0.5, 0.5, 'No Data', ha='center', va='center')
        return
    x_orig = pivot_data.index.values.astype(float)
    for col in pivot_data.columns:
        y_orig = pivot_data[col].values.astype(float)
        pretty_label = DWELLING_LABEL_MAP.get(str(col), str(col))
        _plot_spline_or_line(ax, x_orig, y_orig, label=pretty_label)
    ax.set_xlim(6, 22)
    ax.set_xticks(range(6, 23, 2))
    ax.set_xlabel('Hour of day')
    ax.set_ylabel(ylabel)
    ax.grid(True, linestyle='--', alpha=0.4)
    if use_sci:
        ax.ticklabel_format(axis='y', style='sci', scilimits=(0, 0), useMathText=True)
        ax.yaxis.get_offset_text().set_fontsize(14)  # 稍微调大指数的字体


def plot_panel_1x3(data_total, data_per_fleet, data_per_active, charger_type_name, outfile):
    """
    生成 1*3 的大图 - 包含底部居中的子图编号 (a), (b), (c)
    """
    # 1. 画布尺寸
    fig, axes = plt.subplots(1, 3, figsize=(12, 5))  # 高度稍微增加一点适应大字体

    # 2. 绘制子图
    plot_single_axis(axes[0], data_total, f'{charger_type_name} Energy Demand (kWh)', use_sci=True)
    plot_single_axis(axes[1], data_per_fleet, f'{charger_type_name} (kWh per EV in fleet)', use_sci=False)
    plot_single_axis(axes[2], data_per_active, f'{charger_type_name} (kWh per session)', use_sci=False)

    # ==========================================
    # 【核心功能】添加子图编号到下方中央
    # ==========================================
    subplot_labels = ['(a) Total energy demand', '(b) Energy per fleet EV', '(c) Energy per session']

    # 【位置微调】
    # 因为 axes.labelsize=18 (Hour of day 很大)，所以编号要放得更低
    label_y_pos = -0.25

    for ax, label in zip(axes, subplot_labels):
        ax.text(
            0.5, label_y_pos,
            label,
            transform=ax.transAxes,
            ha='center', va='top',
            fontsize=18, fontweight='normal'
        )
    # ==========================================

    # 3. 统一图例
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(
        handles, labels,
        loc='lower center',
        bbox_to_anchor=(0.5, 0.01),
        ncol=len(labels),
        frameon=False,
        columnspacing=1.5
    )

    # 4. 布局调整
    # bottom=0.35：给18号字的大标题和下方的编号留出足够空间
    plt.subplots_adjust(top=0.92, bottom=0.32, left=0.08, right=0.98, wspace=0.30)

    plt.savefig(outfile, dpi=300, bbox_inches='tight')
    plt.close()
    print(f"[PLOT] Saved composite figure: {outfile}")


# ============== 3. 主程序 ==============
def prepare_metrics(sess_df, ev_counts_dict, filter_type):
    df_sub = sess_df[sess_df['Charger_Type'] == filter_type].copy()
    pivot_energy, _, pivot_n_cars = _aggregate_hourly_energy(df_sub)
    if pivot_energy is None: return None, None, None

    data_total = pivot_energy
    data_per_fleet = pivot_energy.copy()
    for col in data_per_fleet.columns:
        n_ev = ev_counts_dict.get(col, np.nan)
        if n_ev and n_ev > 0:
            data_per_fleet[col] = data_per_fleet[col] / float(n_ev)
        else:
            data_per_fleet[col] = 0.0

    data_per_active = pivot_energy.copy()
    with np.errstate(divide='ignore', invalid='ignore'):
        data_per_active = pivot_energy / pivot_n_cars.replace(0, np.nan)
    data_per_active = data_per_active.fillna(0.0)

    return data_total, data_per_fleet, data_per_active


def main():
    person_dw = load_person_dwelling(POPULATION_FILE)
    ev_counts_dict = compute_ev_counts_by_dwelling(person_dw)
    df_chargers, df_owners = parse_chargers_and_owners(CHARGERS_FILE)
    sess_df = build_sessions_with_dwelling(person_dw, df_chargers, df_owners)

    p_tot, p_flt, p_act = prepare_metrics(sess_df, ev_counts_dict, 'Public')
    if p_tot is not None:
        plot_panel_1x3(p_tot, p_flt, p_act, "Public", "Figure_Dwelling_Public_Panel.png")

    h_tot, h_flt, h_act = prepare_metrics(sess_df, ev_counts_dict, 'Private')
    if h_tot is not None:
        plot_panel_1x3(h_tot, h_flt, h_act, "Home", "Figure_Dwelling_Home_Panel.png")


if __name__ == "__main__":
    main()
