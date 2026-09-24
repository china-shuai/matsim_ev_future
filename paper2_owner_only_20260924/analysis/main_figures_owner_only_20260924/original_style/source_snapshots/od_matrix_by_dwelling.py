import pandas as pd
import geopandas as gpd
import xml.etree.ElementTree as ET
import gzip
import re
import plotly.graph_objects as go
from pathlib import Path

# ============== 0. 路径配置 ==============
POPULATION_FILE = '/Users/S4065267/Matsim_ev/Simulation/100/population_ev_no_soc_attrs_0.3.xml'
CHARGERS_FILE = '/Users/S4065267/Matsim_ev/Simulation/100/chargers_0.3.xml'
ATTEMPTS_CSV = '/Users/S4065267/Matsim_ev/Simulation/output_0.3HC/ITERS/it.100/100.wevc_charging_attempts.csv'
NETWORK_FILE = '/Users/S4065267/Matsim_ev/Simulation/output_0.3HC/output_network.xml.gz'
LGA_SHP = '/Users/S4065267/Downloads/同步空间/A_Paper1/Data/LGA/LGA.shp'

NETWORK_CRS = "EPSG:28355"

# LGA → Inner/Middle/Outer
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
OUT_DIR = Path(__file__).resolve().parent


# ============== 1. 工具函数 ==============
def normalize_lga_name(name: str) -> str:
    if pd.isna(name): return name
    return str(name).replace('(C)', '').replace('(Vic.)', '').strip()


def format_dwelling_label(dwelling: str) -> str:
    mapping = {
        'APARTMENT': 'Apartment',
        'HOUSE_NO_PV': 'House without PV',
        'HOUSE_WITH_PV': 'House with PV',
    }
    return mapping.get(str(dwelling), str(dwelling).replace('_', ' ').title())


# ============== 2. Network ==============
def parse_network_coords(xml_path):
    print(f"[NETWORK] Loading {xml_path} ...")
    link_coords = {}
    nodes = {}
    open_func = gzip.open if xml_path.endswith('.gz') else open
    with open_func(xml_path, 'rb') as f:
        context = ET.iterparse(f, events=('end',))
        for _, elem in context:
            if elem.tag == 'node':
                nodes[elem.get('id')] = (float(elem.get('x')), float(elem.get('y')))
            elif elem.tag == 'link':
                f_node, t_node = elem.get('from'), elem.get('to')
                if f_node in nodes and t_node in nodes:
                    link_coords[elem.get('id')] = (
                        (nodes[f_node][0] + nodes[t_node][0]) / 2.0,
                        (nodes[f_node][1] + nodes[t_node][1]) / 2.0
                    )
                elem.clear()
    return link_coords


# ============== 3. Population ==============
def load_population_home_zones(pop_xml_path, lga_shp_path):
    print(f"[POP] Loading {pop_xml_path} ...")
    tree = ET.parse(pop_xml_path)
    root = tree.getroot()
    records = []
    DW_ATTR_CANDIDATES = ['ev:dwellingType', 'ev:dwellingtype', 'dwellingType']

    for person in root.findall('person'):
        pid = person.get('id')
        dw_type = None
        attrs = person.find('attributes')
        if attrs is not None:
            for a in attrs.findall('attribute'):
                if a.get('name') in DW_ATTR_CANDIDATES:
                    dw_type = (a.text or '').strip();
                    break

        plan = person.find('plan')
        if plan is None: continue
        home_x, home_y = None, None
        for act in plan.findall('activity'):
            if act.get('type') == 'Home':
                home_x = float(act.get('x'));
                home_y = float(act.get('y'));
                break

        if home_x is None:
            first_act = plan.find('activity')
            if first_act is not None:
                home_x = float(first_act.get('x'));
                home_y = float(first_act.get('y'))

        if home_x is not None:
            records.append({'person_id': pid, 'home_x': home_x, 'home_y': home_y, 'dwelling_type': dw_type})

    pop_gdf = gpd.GeoDataFrame(records, geometry=gpd.points_from_xy([r['home_x'] for r in records],
                                                                    [r['home_y'] for r in records]), crs=NETWORK_CRS)
    lga_gdf = gpd.read_file(lga_shp_path).to_crs(NETWORK_CRS)
    lga_gdf['lga_name_norm'] = lga_gdf['lga_name'].apply(normalize_lga_name)

    try:
        joined = gpd.sjoin(pop_gdf, lga_gdf[['lga_name_norm', 'geometry']], predicate='within')
    except:
        joined = gpd.sjoin(pop_gdf, lga_gdf[['lga_name_norm', 'geometry']], op='within')

    joined['homeLGA'] = joined['lga_name_norm'].apply(normalize_lga_name)
    joined['Driver_Home_Zone'] = joined['homeLGA'].map(LGA_TO_ZONE).fillna('Unknown')
    joined = joined[(joined['Driver_Home_Zone'] != 'Unknown') & (~joined['homeLGA'].isin(EXCLUDED_LGAS))].copy()

    lga_gdf['zone'] = lga_gdf['lga_name_norm'].map(LGA_TO_ZONE).fillna('Other')
    return joined[
        ['person_id', 'home_x', 'home_y', 'homeLGA', 'Driver_Home_Zone', 'dwelling_type']].drop_duplicates(), lga_gdf


# ============== 4. Chargers ==============
def parse_chargers_and_owners(xml_path):
    print(f"[CHARGERS] Loading {xml_path} ...")
    open_func = gzip.open if xml_path.endswith('.gz') else open
    with open_func(xml_path, 'rb') as f:
        tree = ET.parse(f)
    chargers, owners = [], []
    for ch in tree.getroot().findall('charger'):
        cid, link = ch.get('id'), ch.get('link')
        chargers.append({'charger_id': cid, 'link_id': link})
        persons_str = next((a.text for a in (ch.find('attributes') or []) if a.get('name') == 'sevc:persons'), None)
        if persons_str:
            for pid in re.split(r'[,\s]+', persons_str.strip()):
                if pid: owners.append({'charger_id': cid, 'link_id': link, 'owner_person_id': pid})
    return pd.DataFrame(chargers).drop_duplicates(), pd.DataFrame(owners)


# ============== 5. Sessions ==============
def build_sessions(pop_home, df_chargers, df_owners, link_coords):
    print(f"[ATTEMPTS] Loading {ATTEMPTS_CSV} ...")
    df_a = pd.read_csv(ATTEMPTS_CSV, sep=';')
    df_a = df_a[(df_a['successful'] == True) & (df_a['charged'] == True)].copy()
    df_a['person_id'] = df_a['person_id'].astype(str)

    pop_home['person_id'] = pop_home['person_id'].astype(str)
    df = df_a.merge(pop_home, on='person_id', how='left')
    df = df.merge(df_chargers, on='charger_id', how='left')
    df['link_xy'] = df['link_id'].map(link_coords)
    df['charger_x'] = df['link_xy'].apply(lambda v: v[0] if isinstance(v, tuple) else None)
    df['charger_y'] = df['link_xy'].apply(lambda v: v[1] if isinstance(v, tuple) else None)

    if not df_owners.empty:
        df_owners['owner_person_id'] = df_owners['owner_person_id'].astype(str)
        df = df.merge(df_owners[['charger_id', 'owner_person_id']], left_on=['charger_id', 'person_id'],
                      right_on=['charger_id', 'owner_person_id'], how='left')
        df['Charger_Type'] = df['owner_person_id'].notna().map(lambda x: 'Private' if x else 'Public')
    else:
        df['Charger_Type'] = 'Public'
    return df


# ============== 6. Attach Charger Zone ==============
def attach_charger_zone(sess_df, lga_gdf):
    df = sess_df.copy()
    df['charger_zone'] = None
    public_df = df[(df['Charger_Type'] == 'Public') & df['charger_x'].notna()].copy()
    if public_df.empty: return df

    gdf_pub = gpd.GeoDataFrame(public_df, geometry=gpd.points_from_xy(public_df['charger_x'], public_df['charger_y']),
                               crs=NETWORK_CRS)
    try:
        joined = gpd.sjoin(gdf_pub, lga_gdf[['zone', 'geometry']], predicate='within')
    except:
        joined = gpd.sjoin(gdf_pub, lga_gdf[['zone', 'geometry']], op='within')

    df.loc[joined.index, 'charger_zone'] = joined['zone'].values
    return df


# ============== 7. Sankey Plot (Color by Zone) ==============

# 【修改点1】定义区域颜色映射 (RGB)
ZONE_COLOR_MAP = {
    'Inner': (215, 48, 39),  # Red
    'Middle': (31, 119, 180),  # Blue
    'Outer': (26, 152, 80)  # Green
}


def get_color_for_zone(zone_name, alpha=1.0):
    rgb = ZONE_COLOR_MAP.get(zone_name, (128, 128, 128))
    return f"rgba({rgb[0]}, {rgb[1]}, {rgb[2]}, {alpha})"


def dwelling_order_key(dw: str):
    if dw is None: return (99, '')
    s = str(dw).lower()
    if 'apart' in s: return (0, s)
    if 'house' in s and 'pv' in s: return (1, s)
    if 'house' in s and 'pv' not in s: return (2, s)
    return (3, s)


def plot_public_sankey(sess_df, zones=('Inner', 'Middle', 'Outer')):
    """
    左列：dwelling_type × home zone
    右列：Public charging zone
    颜色逻辑：全部基于 Zone (Inner/Middle/Outer)
    """
    base = sess_df.copy()
    base = base[
        (base['Charger_Type'] == 'Public') &
        base['dwelling_type'].notna() &
        base['Driver_Home_Zone'].isin(zones) &
        base['charger_zone'].isin(zones)
        ]
    if base.empty:
        print("[SANK] No data.")
        return

    dwellings_sorted = sorted(base['dwelling_type'].dropna().unique().tolist(), key=dwelling_order_key)

    node_labels = []
    node_x, node_y = [], []
    node_colors = []
    node_index = {}

    # --- 左节点: Dwelling @ HomeZone ---
    left_nodes = []
    for hz in zones:
        for dw in dwellings_sorted:
            if not base[(base['dwelling_type'] == dw) & (base['Driver_Home_Zone'] == hz)].empty:
                left_nodes.append((dw, hz))

    n_left = len(left_nodes)
    for i, (dw, hz) in enumerate(left_nodes):
        idx = len(node_labels)
        node_index[('L', dw, hz)] = idx
        node_labels.append(f"{format_dwelling_label(dw)} ({hz})")
        node_x.append(0.001)
        node_y.append(0.04 + i * (0.84 / max(1, n_left - 1)) if n_left > 1 else 0.5)
        # 【修改点2】左侧节点颜色 -> 对应 Home Zone
        node_colors.append(get_color_for_zone(hz, alpha=1.0))

    # --- 右节点: Charger Zone ---
    present_cz = set(base['charger_zone'].dropna().unique().tolist())
    right_zones = [z for z in zones if z in present_cz]

    n_right = len(right_zones)
    for i, cz in enumerate(right_zones):
        idx = len(node_labels)
        node_index[('R', cz)] = idx
        node_labels.append(f"Charging in {cz}")
        node_x.append(0.999)
        node_y.append(0.14 + i * (0.72 / max(1, n_right - 1)) if n_right > 1 else 0.5)
        # 【修改点3】右侧节点颜色 -> 对应 Charger Zone
        node_colors.append(get_color_for_zone(cz, alpha=1.0))

        # --- 连线数据 ---
    agg = base.groupby(['dwelling_type', 'Driver_Home_Zone', 'charger_zone']).size().reset_index(name='n_sessions')

    sources, targets, values, link_colors = [], [], [], []

    for _, row in agg.iterrows():
        dw, hz, cz, v = row['dwelling_type'], row['Driver_Home_Zone'], row['charger_zone'], row['n_sessions']
        if v <= 0: continue

        s_key, t_key = ('L', dw, hz), ('R', cz)
        if s_key not in node_index or t_key not in node_index: continue

        sources.append(node_index[s_key])
        targets.append(node_index[t_key])
        values.append(int(v))

        # 【修改点4】连线颜色 -> 跟随 Driver Home Zone (来源地)
        # 这样可以看出 "住在 Inner 的人" 流向了哪里
        link_colors.append(get_color_for_zone(hz, alpha=0.4))

    if not values: return

    # --- 绘图 ---
    fig = go.Figure(data=[go.Sankey(
        arrangement="snap",
        node=dict(
            pad=15, thickness=20,
            line=dict(color="black", width=0.5),
            label=node_labels,
            x=node_x, y=node_y,
            color=node_colors  # 使用 Zone 颜色
        ),
        link=dict(
            source=sources, target=targets, value=values,
            color=link_colors  # 使用 HomeZone 颜色
        )
    )])

    fig.update_layout(
        font=dict(size=14, family="Times New Roman"),
        width=700, height=500,
        margin=dict(l=70, r=70, t=60, b=110)
    )

    out_html = OUT_DIR / "sankey_public_flow_by_zone_color.html"
    fig.write_html(out_html)
    print(f"[SANK] Saved {out_html}")

    out_png = OUT_DIR / "newplot.png"
    try:
        fig.write_image(out_png, width=700, height=500, scale=2)
        print(f"[SANK] Saved {out_png}")
    except Exception as e:
        print(f"[SANK] PNG export skipped: {e}")


# ============== 8. Main ==============
def main():
    link_coords = parse_network_coords(NETWORK_FILE)
    pop_home, lga_gdf = load_population_home_zones(POPULATION_FILE, LGA_SHP)
    df_chargers, df_owners = parse_chargers_and_owners(CHARGERS_FILE)
    sess_df = build_sessions(pop_home, df_chargers, df_owners, link_coords)
    sess_df = attach_charger_zone(sess_df, lga_gdf)
    plot_public_sankey(sess_df)


if __name__ == "__main__":
    main()
