import pandas as pd
import geopandas as gpd
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
LGA_SHP = '/Users/S4065267/Downloads/同步空间/A_Paper1/Data/LGA/LGA.shp'

NETWORK_CRS = "EPSG:28355"

# LGA -> Inner / Middle / Outer
LGA_TO_ZONE = {
    'Melbourne': 'Inner', 'Yarra': 'Inner', 'Port Phillip': 'Inner',
    'Stonnington': 'Middle', 'Maribyrnong': 'Middle', 'Moonee Valley': 'Middle',
    'Moreland': 'Middle', 'Darebin': 'Middle', 'Banyule': 'Middle',
    'Boroondara': 'Middle', 'Whitehorse': 'Middle', 'Monash': 'Middle',
    'Glen Eira': 'Middle', 'Bayside': 'Middle', 'Hobsons Bay': 'Middle',
    'Kingston': 'Middle', 'Manningham': 'Middle',
    'Brimbank': 'Outer', 'Hume': 'Outer', 'Whittlesea': 'Outer',
    'Nillumbik': 'Outer', 'Maroondah': 'Outer', 'Knox': 'Outer',
    'Yarra Ranges': 'Outer', 'Greater Dandenong': 'Outer', 'Casey': 'Outer',
    'Cardinia': 'Outer', 'Frankston': 'Outer', 'Mornington Peninsula': 'Outer',
    'Wyndham': 'Outer', 'Melton': 'Outer',
    'Mitchell': 'Outer', 'Macedon Ranges': 'Outer',
    'Moorabool': 'Outer', 'Murrindindi': 'Outer'
}

EXCLUDED_LGAS = ['Greater Geelong', 'Greater Geelong (C)']

# 【配置】颜色与样式映射 (保证三张图颜色一致)
ZONE_STYLE = {
    'Inner': {'color': '#d62728', 'label': 'Inner Melbourne'},  # 红
    'Middle': {'color': '#1f77b4', 'label': 'Middle Melbourne'},  # 蓝
    'Outer': {'color': '#2ca02c', 'label': 'Outer Melbourne'}  # 绿
}
PLOT_ORDER = ['Inner', 'Middle', 'Outer']  # 绘图顺序

# 【配置】字体与画布
plt.rcParams.update({
    'font.family': 'Times New Roman',
    'font.size': 11,  # 论文推荐字号
    'axes.labelsize': 18,
    'xtick.labelsize': 14,
    'ytick.labelsize': 14,
    'legend.fontsize': 18
})


# ============== 1. 数据处理工具 (保持不变) ==============
def normalize_lga_name(name: str) -> str:
    if pd.isna(name): return name
    return str(name).replace('(C)', '').replace('(Vic.)', '').strip()


def _aggregate_hourly_by_zone(df_in: pd.DataFrame):
    df = df_in.copy()
    df = df[df['Driver_Home_Zone'].isin(PLOT_ORDER)].copy()
    if df.empty: return None, None, None

    df['hour'] = ((df['charging_start_time'] % 86400) // 3600).astype(int)
    agg = df.groupby(['hour', 'Driver_Home_Zone'], as_index=False).agg(
        total_energy_kWh=('energy_kWh', 'sum'),
        n_sessions=('person_id', 'size'),
        n_cars=('person_id', 'nunique')
    )

    hours_range = pd.Index(range(6, 23), name='hour')

    def pivot_reindex(col):
        p = agg.pivot(index='hour', columns='Driver_Home_Zone', values=col).fillna(0.0)
        return p.reindex(hours_range, fill_value=0.0)

    return (pivot_reindex('total_energy_kWh'),
            pivot_reindex('n_sessions'),
            pivot_reindex('n_cars'))


# ============== 2. 数据加载逻辑 (保持不变) ==============
def load_population_home_zones(pop_xml_path, lga_shp_path):
    print(f"[POP] Loading {pop_xml_path} ...")
    tree = ET.parse(pop_xml_path)
    root = tree.getroot()
    records = []
    for person in root.findall('person'):
        pid = person.get('id')
        plan = person.find('plan')
        if not plan: continue

        # 简化版提取 Home 坐标
        home_act = None
        for act in plan.findall('activity'):
            if act.get('type') == 'Home':
                home_act = act
                break
        if not home_act: home_act = plan.find('activity')  # fallback

        if home_act is not None:
            records.append({
                'person_id': pid,
                'home_x': float(home_act.get('x')),
                'home_y': float(home_act.get('y'))
            })

    pop_gdf = gpd.GeoDataFrame(
        records,
        geometry=gpd.points_from_xy([r['home_x'] for r in records], [r['home_y'] for r in records]),
        crs=NETWORK_CRS
    )

    print(f"[LGA] Loading {lga_shp_path} ...")
    lga_gdf = gpd.read_file(lga_shp_path).to_crs(NETWORK_CRS)
    lga_gdf['lga_name_norm'] = lga_gdf['lga_name'].apply(normalize_lga_name)

    # Spatial Join
    try:
        joined = gpd.sjoin(pop_gdf, lga_gdf[['lga_name_norm', 'geometry']], how='left', predicate='within')
    except TypeError:
        joined = gpd.sjoin(pop_gdf, lga_gdf[['lga_name_norm', 'geometry']], how='left', op='within')

    joined['homeLGA'] = joined['lga_name_norm'].apply(normalize_lga_name)
    joined['Driver_Home_Zone'] = joined['homeLGA'].apply(lambda x: LGA_TO_ZONE.get(x, 'Unknown'))

    pop_home = joined[
        (joined['Driver_Home_Zone'] != 'Unknown') &
        (~joined['homeLGA'].isin(EXCLUDED_LGAS))
        ][['person_id', 'Driver_Home_Zone']].drop_duplicates()

    print(f"[POP] persons with valid Zone: {len(pop_home)}")
    return pop_home


def compute_ev_counts_by_zone(pop_home: pd.DataFrame):
    df = pop_home[pop_home['Driver_Home_Zone'].isin(PLOT_ORDER)]
    return df.groupby('Driver_Home_Zone')['person_id'].nunique().to_dict()


def parse_chargers_owners(xml_path):
    # (同上，保持不变)
    print(f"[CHARGERS] Loading {xml_path} ...")
    open_func = gzip.open if xml_path.endswith('.gz') else open
    with open_func(xml_path, 'rb') as f:
        tree = ET.parse(f)
    root = tree.getroot()
    owners = []
    for ch in root.findall('charger'):
        cid = ch.get('id')
        attrs = ch.find('attributes')
        persons_str = None
        if attrs:
            for a in attrs.findall('attribute'):
                if a.get('name') == 'sevc:persons':
                    persons_str = (a.text or '').strip();
                    break
        if persons_str:
            for pid in re.split(r'[,\s]+', persons_str):
                if pid: owners.append({'charger_id': cid, 'owner_person_id': pid})
    return pd.DataFrame(owners)


def build_sessions(pop_home, df_owners):
    print(f"[ATTEMPTS] Loading {ATTEMPTS_CSV} ...")
    df_a = pd.read_csv(ATTEMPTS_CSV, sep=';')
    df_a = df_a[(df_a['successful'] == True) & (df_a['charged'] == True)].copy()
    df_a['person_id'] = df_a['person_id'].astype(str)

    pop_home = pop_home.copy()
    pop_home['person_id'] = pop_home['person_id'].astype(str)

    df = df_a.merge(pop_home, on='person_id', how='left')

    if not df_owners.empty:
        df_owners = df_owners.copy()
        df_owners['owner_person_id'] = df_owners['owner_person_id'].astype(str)
        df = df.merge(df_owners, left_on=['charger_id', 'person_id'], right_on=['charger_id', 'owner_person_id'],
                      how='left')
        df['Charger_Type'] = df['owner_person_id'].notna().map(lambda x: 'Home charging' if x else 'Public')
    else:
        df['Charger_Type'] = 'Public'

    df = df[df['Driver_Home_Zone'].isin(PLOT_ORDER)].copy()
    print("[SESS] Charger_Type counts:\n", df['Charger_Type'].value_counts())
    return df


# ============== 3. 绘图核心 (重构版) ==============

def _plot_spline_or_line(ax, x_orig, y_orig, label, color):
    x_orig = np.asarray(x_orig, dtype=float)
    y_orig = np.asarray(y_orig, dtype=float)

    if np.count_nonzero(y_orig) <= 3:
        ax.plot(x_orig, y_orig, linewidth=2, linestyle='--', label=label, color=color)
        return

    x_smooth = np.linspace(x_orig.min(), x_orig.max(), 400)
    try:
        spline = make_interp_spline(x_orig, y_orig, k=3)
        y_smooth = np.maximum(spline(x_smooth), 0)  # 修正负值
        ax.plot(x_smooth, y_smooth, linewidth=2, label=label, color=color)
    except Exception:
        ax.plot(x_orig, y_orig, linewidth=2, linestyle='--', label=label, color=color)


def plot_single_axis(ax, pivot_data, ylabel, use_sci=False):
    """ 单个坐标轴绘图 """
    if pivot_data is None or pivot_data.empty:
        ax.text(0.5, 0.5, 'No Data', ha='center', va='center')
        return

    x_orig = pivot_data.index.values.astype(float)

    # 按照固定的顺序 (Inner -> Middle -> Outer) 绘图
    for zone in PLOT_ORDER:
        if zone in pivot_data.columns:
            y_orig = pivot_data[zone].values.astype(float)
            style = ZONE_STYLE.get(zone, {'color': 'black', 'label': zone})
            _plot_spline_or_line(ax, x_orig, y_orig, label=style['label'], color=style['color'])

    ax.set_xlim(6, 22)
    ax.set_xticks(range(6, 23, 2))
    ax.set_xlabel('Hour of day')
    ax.set_ylabel(ylabel)
    ax.grid(True, linestyle='--', alpha=0.4)

    # 【核心】针对 Total Demand 开启科学计数法
    if use_sci:
        ax.ticklabel_format(axis='y', style='sci', scilimits=(0, 0), useMathText=True)
        ax.yaxis.get_offset_text().set_fontsize(11)


def plot_panel_1x3(data_total, data_per_fleet, data_per_active, charger_type_name, outfile):
    """
    生成 1*3 的大图 - 样式与 Zone 代码完全统一
    【新增】在每个子图下方中央添加编号 (a), (b), (c)
    """
    # 1. 统一画布尺寸 (12, 4)
    fig, axes = plt.subplots(1, 3, figsize=(12, 4))

    # 2. 绘制子图
    # (a) Total Demand (开启科学计数法)
    plot_single_axis(
        axes[0], data_total,
        f'{charger_type_name} Energy Demand (kWh)',
        use_sci=True
    )

    # (b) Per Fleet (不开启科学计数法)
    plot_single_axis(
        axes[1], data_per_fleet,
        f'{charger_type_name} (kWh per EV in fleet)',
        use_sci=False
    )

    # (c) Per Active (不开启科学计数法)
    plot_single_axis(
        axes[2], data_per_active,
        f'{charger_type_name} (kWh per session)',
        use_sci=False
    )

    # ==========================================
    # 【新增功能】添加子图编号到下方中央
    # ==========================================
    # 定义要显示的标签内容
    subplot_labels = ['(a) Total energy demand', '(b) Energy per fleet EV', '(c) Energy per session']

    # 设定标签在Y轴方向的偏移量。
    # 0 表示轴线，负数表示轴下方。-0.25 大约在 "Hour of day" 标签的下方。
    label_y_pos = -0.25

    # 遍历三个坐标轴和对应的标签
    for ax, label in zip(axes, subplot_labels):
        ax.text(
            0.5, label_y_pos,  # 坐标 (x=中间, y=轴下方)
            label,  # 文本内容
            transform=ax.transAxes,  # 使用相对坐标系(基于子图大小)
            ha='center',  # horizontal alignment: 水平居中
            va='top',  # vertical alignment: 顶部对齐(文字上边缘对齐参考点)
            fontsize=18,  # 字体大小略大于正文
            fontweight='normal'  # 如果需要加粗可改为 'bold'
        )
    # ==========================================

    # 3. 统一图例位置 (lower center, 间距优化)
    # 我们需要确保图例在子图编号的更下方
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(
        handles, labels,
        loc='lower center',
        bbox_to_anchor=(0.5, 0.01),
        ncol=len(labels),
        frameon=False,
        columnspacing=1.5
    )

    # 4. 统一留白 (bottom=0.30)
    # 这个底部留白现在要容纳：X轴标签 + 子图编号 + 总图例，0.30 应该是刚好够用的。
    plt.subplots_adjust(top=0.99, bottom=0.35, left=0.08, right=0.98, wspace=0.30)

    # 使用 bbox_inches='tight' 可以确保所有添加的文本都不会被裁剪掉
    plt.savefig(outfile, dpi=300, bbox_inches='tight')
    plt.close()
    print(f"[PLOT] Saved composite figure: {outfile}")


# ============== 4. 准备数据与主程序 ==============

def prepare_metrics(sess_df, ev_counts, filter_type):
    df_sub = sess_df[sess_df['Charger_Type'] == filter_type].copy()
    pivot_energy, _, pivot_n_cars = _aggregate_hourly_by_zone(df_sub)

    if pivot_energy is None: return None, None, None

    # 1. Total
    d_total = pivot_energy

    # 2. Per Fleet
    d_fleet = pivot_energy.copy()
    for col in d_fleet.columns:
        n_ev = ev_counts.get(col, np.nan)
        if n_ev and n_ev > 0:
            d_fleet[col] = d_fleet[col] / float(n_ev)
        else:
            d_fleet[col] = 0.0

    # 3. Per Active
    d_active = pivot_energy.copy()
    with np.errstate(divide='ignore', invalid='ignore'):
        d_active = pivot_energy / pivot_n_cars.replace(0, np.nan)
    d_active = d_active.fillna(0.0)

    return d_total, d_fleet, d_active


def main():
    # 1. 加载数据
    pop_home = load_population_home_zones(POPULATION_FILE, LGA_SHP)
    ev_counts = compute_ev_counts_by_zone(pop_home)
    df_owners = parse_chargers_owners(CHARGERS_FILE)
    sess_df = build_sessions(pop_home, df_owners)

    # 2. 绘制 Public
    p_tot, p_flt, p_act = prepare_metrics(sess_df, ev_counts, 'Public')
    if p_tot is not None:
        plot_panel_1x3(p_tot, p_flt, p_act, "Public", "Figure_Zone_Public_Panel.png")

    # 3. 绘制 Home
    h_tot, h_flt, h_act = prepare_metrics(sess_df, ev_counts, 'Home charging')
    if h_tot is not None:
        plot_panel_1x3(h_tot, h_flt, h_act, "Home", "Figure_Zone_Home_Panel.png")


if __name__ == "__main__":
    main()