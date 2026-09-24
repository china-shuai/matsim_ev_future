import pandas as pd
import geopandas as gpd
from shapely.geometry import Point
import matplotlib.pyplot as plt
import xml.etree.ElementTree as ET
import gzip
import re
import numpy as np
import warnings
import matplotlib.patches as mpatches
import contextily as cx  # 底图

# 为了干掉 numpy 关于 MaskedArray 的无关 warning
warnings.filterwarnings(
    "ignore",
    message="Warning: 'partition' will ignore the 'mask' of the MaskedArray."
)

OUTPUT_DPI = 500

# ============== 0. 基本路径配置（按你当前使用的改） ==============
POPULATION_FILE = '/Users/S4065267/Matsim_ev/Simulation/100/population0.3HC.xml'
CHARGERS_FILE = '/Users/S4065267/Matsim_ev/Simulation/100/chargers_0.31.xml'
ATTEMPTS_CSV = '/Users/S4065267/Matsim_ev/Simulation/output_0.3HC/ITERS/it.100/100.wevc_charging_attempts.csv'
NETWORK_FILE = '/Users/S4065267/Matsim_ev/Simulation/output_0.3HC/output_network.xml.gz'
LGA_SHP = '/Users/S4065267/Downloads/同步空间/A_Paper1/Data/LGA/LGA.shp'

NETWORK_CRS = "EPSG:28355"

# LGA → Inner/Middle/Outer 分组
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


def format_dwelling_label(dwelling: str) -> str:
    mapping = {
        "APARTMENT": "Apartment",
        "HOUSE_NO_PV": "House without PV",
        "HOUSE_WITH_PV": "House with PV",
    }
    return mapping.get(dwelling, dwelling.replace('_', ' ').lower().capitalize())

plt.rcParams.update({
    'font.family': 'Times New Roman',
    'font.size': 12
})


# ============== 1. 通用小工具 ==============
def normalize_lga_name(name: str) -> str:
    if pd.isna(name):
        return name
    return (
        str(name)
        .replace('(C)', '')
        .replace('(Vic.)', '')
        .strip()
    )


# ============== 2. Network：link -> (x, y) ==============
def parse_network_coords(xml_path):
    print(f"[NETWORK] Loading {xml_path} ...")
    link_coords = {}
    nodes = {}

    open_func = gzip.open if xml_path.endswith('.gz') else open
    with open_func(xml_path, 'rb') as f:
        context = ET.iterparse(f, events=('end',))
        for event, elem in context:
            tag = elem.tag
            if tag == 'node':
                nid = elem.get('id')
                x = float(elem.get('x'))
                y = float(elem.get('y'))
                nodes[nid] = (x, y)
            elif tag == 'link':
                lid = elem.get('id')
                f_node = elem.get('from')
                t_node = elem.get('to')
                if f_node in nodes and t_node in nodes:
                    x = (nodes[f_node][0] + nodes[t_node][0]) / 2.0
                    y = (nodes[f_node][1] + nodes[t_node][1]) / 2.0
                    link_coords[lid] = (x, y)
                elem.clear()

    print(f"[NETWORK] links with coords: {len(link_coords)}")
    return link_coords


# ============== 3. Population：person -> home_xy + dwelling_type + homeLGA + zone ==============
def load_population_home_zones(pop_xml_path, lga_shp_path):
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

        plan = person.find('plan')
        if plan is None:
            continue

        home_x = None
        home_y = None
        for act in plan.findall('activity'):
            if act.get('type') == 'Home':
                home_x = float(act.get('x'))
                home_y = float(act.get('y'))
                break

        if home_x is None or home_y is None:
            first_act = plan.find('activity')
            if first_act is not None:
                home_x = float(first_act.get('x'))
                home_y = float(first_act.get('y'))

        if home_x is not None and home_y is not None:
            records.append({
                'person_id': pid,
                'home_x': home_x,
                'home_y': home_y,
                'dwelling_type': dw_type
            })

    pop_gdf = gpd.GeoDataFrame(
        records,
        geometry=gpd.points_from_xy(
            [r['home_x'] for r in records],
            [r['home_y'] for r in records]
        ),
        crs=NETWORK_CRS
    )
    print(f"[POP] persons with home_xy: {len(pop_gdf)}")

    print(f"[LGA] Loading {lga_shp_path} ...")
    lga_gdf = gpd.read_file(lga_shp_path)
    lga_gdf = lga_gdf.to_crs(NETWORK_CRS)

    if 'lga_name' not in lga_gdf.columns:
        raise ValueError("LGA.shp 中没有 'lga_name' 字段")

    lga_gdf['lga_name_norm'] = lga_gdf['lga_name'].apply(normalize_lga_name)

    try:
        joined = gpd.sjoin(
            pop_gdf,
            lga_gdf[['lga_name_norm', 'geometry']],
            how='left',
            predicate='within'
        )
    except TypeError:
        joined = gpd.sjoin(
            pop_gdf,
            lga_gdf[['lga_name_norm', 'geometry']],
            how='left',
            op='within'
        )

    joined = joined.rename(columns={'lga_name_norm': 'homeLGA'})
    joined['homeLGA'] = joined['homeLGA'].apply(normalize_lga_name)
    joined['Driver_Home_Zone'] = joined['homeLGA'].apply(
        lambda x: LGA_TO_ZONE.get(x, 'Unknown')
    )

    joined = joined[
        (joined['Driver_Home_Zone'] != 'Unknown') &
        (~joined['homeLGA'].isin(EXCLUDED_LGAS))
        ].copy()

    pop_home = joined[['person_id', 'home_x', 'home_y', 'homeLGA',
                       'Driver_Home_Zone', 'dwelling_type']].drop_duplicates()
    print(f"[POP] persons with homeLGA+zone: {len(pop_home)}")

    lga_gdf['zone'] = lga_gdf['lga_name_norm'].map(LGA_TO_ZONE).fillna('Other')

    return pop_home, lga_gdf


# ============== 4. Chargers：识别家充 owner ==============
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
                owners.append({'charger_id': cid, 'link_id': link, 'owner_person_id': pid})

    df_chargers = pd.DataFrame(chargers).drop_duplicates()
    df_owners = pd.DataFrame(owners)
    print(f"[CHARGERS] chargers: {len(df_chargers)}, pairs: {len(df_owners)}")
    return df_chargers, df_owners


# ============== 5. 会话数据构造 ==============
def build_sessions(pop_home, df_chargers, df_owners, link_coords):
    print(f"[ATTEMPTS] Loading {ATTEMPTS_CSV} ...")
    df_a = pd.read_csv(ATTEMPTS_CSV, sep=';')
    df_a = df_a[(df_a['successful'] == True) & (df_a['charged'] == True)].copy()
    df_a['person_id'] = df_a['person_id'].astype(str)
    df_a['duration_h'] = (df_a['charging_end_time'] - df_a['charging_start_time']) / 3600.0

    pop_home = pop_home.copy()
    pop_home['person_id'] = pop_home['person_id'].astype(str)
    df = df_a.merge(pop_home, on='person_id', how='left')
    print("[SESS] after merge population:", len(df))

    df = df.merge(df_chargers, on='charger_id', how='left')
    df['link_xy'] = df['link_id'].map(link_coords)
    df['charger_x'] = df['link_xy'].apply(lambda xy: xy[0] if isinstance(xy, tuple) else None)
    df['charger_y'] = df['link_xy'].apply(lambda xy: xy[1] if isinstance(xy, tuple) else None)

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


# ============== 6. LGA 聚合 ==============
def aggregate_by_lga(sess_df, lga_gdf):
    lga_gdf = lga_gdf.copy()
    lga_gdf['lga_name_norm'] = lga_gdf['lga_name'].apply(normalize_lga_name)

    public_df = sess_df[(sess_df['Charger_Type'] == 'Public') &
                        sess_df['charger_x'].notna() &
                        sess_df['charger_y'].notna()].copy()
    gdf_pub = gpd.GeoDataFrame(
        public_df,
        geometry=gpd.points_from_xy(public_df['charger_x'], public_df['charger_y']),
        crs=NETWORK_CRS
    )

    try:
        joined_pub = gpd.sjoin(gdf_pub, lga_gdf[['lga_name_norm', 'geometry']], how='left', predicate='within')
    except TypeError:
        joined_pub = gpd.sjoin(gdf_pub, lga_gdf[['lga_name_norm', 'geometry']], how='left', op='within')

    joined_pub = joined_pub.rename(columns={'lga_name_norm': 'stat_LGA'})
    joined_pub['stat_LGA'] = joined_pub['stat_LGA'].apply(normalize_lga_name)

    private_df = sess_df[(sess_df['Charger_Type'] == 'Private') & sess_df['homeLGA'].notna()].copy()
    private_df['stat_LGA'] = private_df['homeLGA']

    cols = ['person_id', 'energy_kWh', 'duration_h', 'Driver_Home_Zone', 'Charger_Type', 'stat_LGA']
    df_all = pd.concat([joined_pub[cols], private_df[cols]], ignore_index=True)
    df_all = df_all[~df_all['stat_LGA'].isin(EXCLUDED_LGAS)].copy()

    agg = df_all.groupby(['Driver_Home_Zone', 'Charger_Type', 'stat_LGA'], as_index=False).agg(
        total_energy_kWh=('energy_kWh', 'sum'),
        total_duration_h=('total_duration_h', 'sum') if 'total_duration_h' in df_all.columns else ('duration_h', 'sum'),
        n_sessions=('person_id', 'size')
    )
    agg = agg.rename(columns={'stat_LGA': 'LGA'})
    print("[AGG] rows:", len(agg))
    return agg


# ============== 7. Hexbin 绘图 (放大版) ==============
def plot_lga_hexbin_maps_by_dwelling(sess_df, lga_gdf,
                                     value_cols=('energy_kWh', 'duration_h'),
                                     gridsize=60,
                                     clip_q=0.98):
    """
    针对当前数据的有效范围进行放大显示，去除四周大量空白区域。
    """
    value_labels = {
        'energy_kWh': 'Total charging energy (kWh)',
        'duration_h': 'Total charging duration (h)'
    }

    # ---------- 1) 预计算 vmax 和 紧凑的 Bounding Box ----------
    global_vmax = {}

    # 存储不同组别的数据范围 (xmin, xmax, ymin, ymax)
    # 键为 (value_col, ctype)，值为该组合下所有 dwelling 数据点的 Bounding Box
    global_extent_map = {}

    for value_col in value_cols:
        for ctype in ['Public', 'Private']:
            mask_type = (sess_df['Charger_Type'] == ctype)
            data_all = sess_df[mask_type].dropna(subset=['hex_x', 'hex_y', value_col])
            data_all = data_all[data_all[value_col] > 0]

            if data_all.empty:
                global_vmax[(value_col, ctype)] = None
                global_extent_map[(value_col, ctype)] = None
                continue

            # 计算紧凑的 Extent
            # 获取当前类型下所有点的 min/max
            cur_xmin, cur_xmax = data_all['hex_x'].min(), data_all['hex_x'].max()
            cur_ymin, cur_ymax = data_all['hex_y'].min(), data_all['hex_y'].max()

            # 加上一点 padding (例如 5%)，防止点贴着边
            dx = cur_xmax - cur_xmin
            dy = cur_ymax - cur_ymin
            pad_x = dx * 0.05 if dx > 0 else 1000
            pad_y = dy * 0.05 if dy > 0 else 1000

            # 存入 map，供后面绘图使用
            # 这样同一行的所有子图（同一 Charger Type）会共用这个紧凑的 Extent
            global_extent_map[(value_col, ctype)] = (
                cur_xmin - pad_x, cur_xmax + pad_x,
                cur_ymin - pad_y, cur_ymax + pad_y
            )

            # 计算 vmax
            fig_tmp, ax_tmp = plt.subplots()
            hb_tmp = ax_tmp.hexbin(
                data_all['hex_x'],
                data_all['hex_y'],
                C=data_all[value_col],
                reduce_C_function=np.sum,
                gridsize=gridsize
            )
            arr = hb_tmp.get_array()
            plt.close(fig_tmp)

            arr_pos = arr[arr > 0]
            if arr_pos.size == 0:
                global_vmax[(value_col, ctype)] = None
                continue

            vmax = float(np.quantile(arr_pos, clip_q))
            global_vmax[(value_col, ctype)] = vmax
            print(f"[HEX-DW] {value_col} - {ctype}: vmax={vmax:.2f}")

    dwellings = sorted(sess_df['dwelling_type'].dropna().unique())

    # ---------- 2) 绘图 ----------
    for value_col in value_cols:
        for ctype in ['Public', 'Private']:
            vmax = global_vmax.get((value_col, ctype))
            extent = global_extent_map.get((value_col, ctype))

            if vmax is None or extent is None:
                print(f"[HEX-DW] Skip {value_col} - {ctype} (no data)")
                continue

            dwellings_with_data = []
            for dw in dwellings:
                sub_check = sess_df[(sess_df['Charger_Type'] == ctype) &
                                    (sess_df['dwelling_type'] == dw)]
                sub_check = sub_check.dropna(subset=['hex_x', 'hex_y', value_col])
                sub_check = sub_check[sub_check[value_col] > 0]
                if not sub_check.empty:
                    dwellings_with_data.append(dw)

            if not dwellings_with_data:
                continue

            n_dw = len(dwellings_with_data)

            fig, axes = plt.subplots(
                1, n_dw,
                figsize=(2.7 * n_dw, 4.0),
                sharex=True,
                sharey=True
            )
            if n_dw == 1:
                axes = [axes]

            plt.subplots_adjust(
                left=0.03,
                right=0.88,
                top=0.90,
                bottom=0.12,
                wspace=0.01
            )

            cax = fig.add_axes([0.90, 0.18, 0.015, 0.64])
            hb_for_cbar = None

            for ax, dw in zip(axes, dwellings_with_data):
                sub = sess_df[(sess_df['Charger_Type'] == ctype) &
                              (sess_df['dwelling_type'] == dw)].copy()
                sub = sub.dropna(subset=['hex_x', 'hex_y', value_col])
                sub = sub[sub[value_col] > 0]
                if sub.empty:
                    ax.set_axis_off()
                    continue

                # 【关键】使用动态计算的紧凑 Extent
                xmin_p, xmax_p, ymin_p, ymax_p = extent
                ax.set_xlim(xmin_p, xmax_p)
                ax.set_ylim(ymin_p, ymax_p)

                try:
                    cx.add_basemap(
                        ax,
                        source=cx.providers.CartoDB.Positron,
                        crs=NETWORK_CRS,
                        attribution=False,
                        zorder=0
                    )
                except Exception as e:
                    print(f"[WARN] add_basemap failed: {e}")

                hb = ax.hexbin(
                    sub['hex_x'],
                    sub['hex_y'],
                    C=sub[value_col],
                    reduce_C_function=np.sum,
                    gridsize=gridsize,
                    cmap='OrRd',
                    mincnt=1,
                    # extent 参数可以不传，hexbin 会自动适应数据
                    # 但为了保证 grid 大小一致，建议还是传一下，或者仅依赖 xlim/ylim 裁剪
                    extent=extent,
                    vmin=0,
                    vmax=vmax,
                    alpha=0.7,
                    linewidths=0.2,
                    edgecolors='k',
                    zorder=2
                )
                hb_for_cbar = hb

                ax.set_aspect('equal')
                ax.set_axis_off()

                ax.text(
                    0.5, -0.05,
                    format_dwelling_label(str(dw)),
                    transform=ax.transAxes,
                    ha='center',
                    va='top',
                    fontsize=13
                )

            if hb_for_cbar is not None:
                cbar = fig.colorbar(hb_for_cbar, cax=cax)
                cbar.set_label(value_labels[value_col], fontsize=13)
                cbar.ax.tick_params(labelsize=11)

            out_name = f"hexbin_{ctype}_{value_col}_zoomed.png"
            fig.savefig(out_name, dpi=OUTPUT_DPI)
            plt.close()
            print(f"[HEX-DW] Saved {out_name}")


# ============== 8. 主程序入口 ==============
def main():
    link_coords = parse_network_coords(NETWORK_FILE)
    pop_home, lga_gdf = load_population_home_zones(POPULATION_FILE, LGA_SHP)
    df_chargers, df_owners = parse_chargers_and_owners(CHARGERS_FILE)
    sess_df = build_sessions(pop_home, df_chargers, df_owners, link_coords)

    sess_df['hex_x'] = np.where(sess_df['Charger_Type'] == 'Private', sess_df['home_x'], sess_df['charger_x'])
    sess_df['hex_y'] = np.where(sess_df['Charger_Type'] == 'Private', sess_df['home_y'], sess_df['charger_y'])

    agg_df = aggregate_by_lga(sess_df, lga_gdf)
    agg_df.to_csv("lga_energy_duration_zoomed.csv", index=False)

    plot_lga_hexbin_maps_by_dwelling(
        sess_df,
        lga_gdf,
        value_cols=('energy_kWh', 'duration_h'),
        gridsize=60,
        clip_q=0.98
    )


if __name__ == "__main__":
    main()
