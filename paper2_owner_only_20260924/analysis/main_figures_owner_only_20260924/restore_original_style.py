"""Reuse the author's actual plotting functions, with corrected A/B data only.

No source files in PyCharmMiscProject are modified. Style code is snapshotted.
"""
from pathlib import Path
import hashlib, json, types, socket
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.figure import Figure
import contextily as cx

HERE=Path(__file__).resolve().parent
OUT=HERE/'original_style'
SNAP=OUT/'source_snapshots'
ORIG=Path('/Users/S4065267/PyCharmMiscProject/paper2')
DATA=HERE/'figures'
OUT.mkdir(exist_ok=True);SNAP.mkdir(exist_ok=True)
socket.setdefaulttimeout(20)
cx.set_cache_dir(str(OUT/'basemap_cache'))
PROVENANCE=[]

def read_source(relative):
    p=ORIG/relative
    text=p.read_text()
    dst=SNAP/relative;dst.parent.mkdir(parents=True,exist_ok=True)
    # Verbatim source snapshot, not a modified source file.
    import shutil
    shutil.copy2(p,dst)
    PROVENANCE.append({'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
    return text

def load(relative, substitutions=()):
    text=read_source(relative)
    for old,new in substitutions:
        assert old in text,old
        text=text.replace(old,new)
    m=types.ModuleType('reference_'+str(len(PROVENANCE)))
    m.__file__=str(ORIG/relative)
    exec(compile(text,m.__file__,'exec'),m.__dict__)
    return m

original_save=Figure.savefig
def save_with_svg(fig,fname,*args,**kwargs):
    original_save(fig,fname,*args,**kwargs)
    if str(fname).endswith('.pdf'):
        original_save(fig,Path(fname).with_suffix('.svg'),bbox_inches='tight')
Figure.savefig=save_with_svg

BASEMAPS=[]
original_basemap=cx.add_basemap
def checked_basemap(*args,**kwargs):
    kwargs['source']=cx.providers.OpenStreetMap.Mapnik
    kwargs['headers']={'User-Agent':'EVSimulationPaperFigures/1.0 (local academic figure renderer)'}
    kwargs['attribution']='© OpenStreetMap contributors'
    kwargs['attribution_size']=5
    ax=args[0]
    previous_images=len(ax.images)
    result=original_basemap(*args,**kwargs)
    # Restyle only the basemap raster; leave charging hexagons and attribution intact.
    for artist in list(ax.images)[previous_images:]:
        raster=np.asarray(artist.get_array()).astype(float)
        if raster.max()>1:
            raster/=255.0
        gray=raster[...,:3] @ np.array([0.2126,0.7152,0.0722])
        pale=0.72+0.28*gray
        raster[...,:3]=pale[...,None]
        artist.set_data(np.clip(raster,0,1))
    BASEMAPS.append({'source':'OpenStreetMap.Mapnik','loaded':True,'style':'grayscale, 72% white blend'})
    return result
cx.add_basemap=checked_basemap

def reset():
    plt.rcdefaults()

def metrics(dim,loc):
    name='Figure_03_dwelling_A_data.csv' if dim=='dwelling' else 'Figure_06_zones_A_data.csv'
    d=pd.read_csv(DATA/name);d=d[d.location.eq(loc)]
    return tuple(d.pivot(index='hour',columns='group',values=key).reindex(range(6,23)).fillna(0)
                 for key in ['energy','per_fleet','per_starting_EV'])

def panels():
    for dim,num,source,combined in [
        ('dwelling','03','dwelling type/figure 1.py','dwelling type/combined_panels/combine_public_home_panels.py'),
        ('zone','06','zone/figure1.py','zone/combined_panels/combine_zone_public_home_panels.py')]:
        reset();m=load(source)
        c=load(combined,[('Energy per session','Energy per starting EV'),('kWh per session','kWh per starting EV')])
        c.plot_combined_2x3(m,metrics(dim,'Public'),metrics(dim,'Home'),OUT/f'Figure_{num}_{dim}_A')

def dwelling_maps():
    reset();hm=load('hotmap2.py');q=load('dwelling type/quantile_hexbin_energy_by_dwelling.py')
    d=pd.read_csv(DATA/'sessions_A.csv').rename(columns={'dwelling':'dwelling_type','x':'charger_x','y':'charger_y'})
    d['Charger_Type']=d.location.map({'Home':'Private','Public':'Public'})
    # Supply the audited, corrected sessions to unchanged original rendering code.
    hm.parse_network_coords=lambda *_:None
    hm.load_population_home_zones=lambda *_:(None,None)
    hm.parse_chargers_and_owners=lambda *_:(None,None)
    hm.build_sessions=lambda *_:d.copy()
    q.HERE=OUT
    q.plot_quantile_maps(hm)
    for typ,num in [('Public','04'),('Private','05')]:
        for ext in ['png','pdf','svg']:
            (OUT/f'hexbin_{typ}_energy_kWh_zoomed_quantile.{ext}').rename(OUT/f'Figure_{num}_{typ.lower()}_spatial_A.{ext}')

def sankey():
    reset();m=load('od_matrix_by_dwelling.py');m.OUT_DIR=OUT
    d=pd.read_csv(DATA/'sessions_A.csv').rename(columns={'dwelling':'dwelling_type','zone':'Driver_Home_Zone','charging_zone':'charger_zone'})
    d['Charger_Type']=d.location.map({'Home':'Private','Public':'Public'})
    # Original Plotly Sankey; additionally export vector formats from the same figure.
    import plotly.graph_objects as go
    writer=go.Figure.write_html
    captured=[]
    def capture(fig,*args,**kwargs):
        captured.append(fig)
        return writer(fig,*args,**kwargs)
    go.Figure.write_html=capture
    try:m.plot_public_sankey(d)
    finally:go.Figure.write_html=writer
    fig=captured[0]
    for ext in ['png','pdf','svg']:
        fig.write_image(OUT/f'Figure_07_public_sessions_flow_A.{ext}',width=700,height=500,scale=2)

def policy():
    reset();text=read_source('HC/Figure1 1x3.py')
    prefix=text[:text.index('data_store = {}')]
    prefix=prefix.replace('OUTPUT_DIR.mkdir(parents=True, exist_ok=True)','')
    env={'__name__':'reference_policy','__file__':str(ORIG/'HC/Figure1 1x3.py')}
    exec(compile(prefix,env['__file__'],'exec'),env)
    curves=pd.read_csv(DATA/'Figure_08_data.csv')
    store={}
    for s in ['A','B']:
        store[s]={}
        for i,key in enumerate(['total_public','apt_home','apt_public']):
            d=curves[curves.scenario.eq(s)&curves.panel.eq(i)].sort_values('hour')
            store[s][key]=pd.Series(d.power_kW.to_numpy(),index=d.hour.to_numpy())
    env.update(data_store=store,SCENARIOS=['A','B'],OUTPUT_DIR=OUT,OUT_NAME='Figure_08_access_AB',
               legend_label_from_scenario=lambda s:'Apartment HC '+('56%' if s=='A' else '95%'))
    plot=text[text.index('fig, axes = plt.subplots(1, 3, figsize=(12, 4.5))'):]
    plot=plot.replace('Public Energy Demand (kWh)','Public Charging Load (kW)').replace('Home Energy Demand (kWh)','Home Charging Load (kW)')
    # Keep original font, placement and words; wrap only to avoid overlapping captions.
    for label in ['Total Public','Apartment Home','Apartment Public']:
        plot=plot.replace(label+' Charging Demand',label+'\\nCharging Demand')
    exec(compile(plot,env['__file__'],'exec'),env)

def policy_map():
    reset();m=load('HC/Public_demand_2scenarios_hexbin_APARTMENT.py')
    items=[]
    for side in ['A','B']:
        d=pd.read_csv(DATA/f'sessions_{side}.csv')
        d=d[d.location.eq('Public')&d.dwelling.eq('APARTMENT')]
        items.append({'df':d,'title':'Apartment HC '+('56%' if side=='A' else '95%')})
    m.OUT_PNG=OUT/'Figure_09_apartment_public_AB.png';m.OUT_PDF=OUT/'Figure_09_apartment_public_AB.pdf'
    m.plot_1x2_shared_cbar(items)

def policy_zone():
    reset();m=load('publicvspri/public_energy_change_by_zone_scenarios.py')
    m.SCENARIOS=['A','B'];m.scenario_label=lambda s:'56% HC' if s=='A' else '95% HC'
    d=pd.read_csv(DATA/'Figure_10_data.csv')
    m.plot_metric_single(d,'kWh_per_EV','Public charging energy (kWh per EV)',OUT/'Figure_10_public_energy_by_zone_AB')

if __name__=='__main__':
    import sys
    stages={'panels':panels,'maps':dwelling_maps,'sankey':sankey,'policy':policy,'policy_map':policy_map,'zone':policy_zone}
    selected=sys.argv[1:] or list(stages)
    for name in selected:
        print('STAGE',name,flush=True);stages[name]()
    (OUT/('provenance_'+ '_'.join(selected)+'.json')).write_text(json.dumps({'sources':PROVENANCE,'basemaps':BASEMAPS,'data':str(DATA)},indent=2))
    print('DONE',flush=True)
