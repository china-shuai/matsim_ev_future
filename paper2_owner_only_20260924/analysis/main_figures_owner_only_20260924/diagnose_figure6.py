from pathlib import Path
import gzip,re,json,xml.etree.ElementTree as ET
import numpy as np
import pandas as pd
from scipy.interpolate import make_interp_spline

HERE=Path(__file__).resolve().parent
OUT=HERE/'figure6_diagnostic';OUT.mkdir(exist_ok=True)
RUN=HERE.parent/'mainline_owner_only_20260923'
IT=RUN/'outputs/A_aptHC56_houseHC95/ITERS/it.100'
S=pd.read_csv(HERE/'figures/sessions_A.csv',dtype={'person_id':str,'vehicle_id':str})
S['duration_h']=(S.charging_end_time-S.charging_start_time)/3600
D=S[((S.zone=='Outer')&(S.location=='Public')|(S.zone=='Inner')&(S.location=='Home'))&S.hour.between(7,13)].copy()
targets=set(D.vehicle_id)
root=ET.parse(RUN/'inputs/06_ev_vehicles_random_soc.xml').getroot()
ns={'v':'http://www.matsim.org/files/dtd'}
capacities={v.get('id'):float(v.find('.//v:attribute[@name="energyCapacityInKWhOrLiters"]',ns).text) for v in root.findall('v:vehicleType',ns) if v.find('.//v:attribute[@name="energyCapacityInKWhOrLiters"]',ns) is not None}
vehicles={v.get('id'):capacities[v.get('type')] for v in root.findall('v:vehicle',ns) if v.get('type') in capacities}
events=[];active={}
with gzip.open(next(IT.glob('*.events.xml.gz')),'rt') as h:
    for line in h:
        if not any(t in line for t in ['type="charging_start"','type="energy_charged"','type="charging_end"']):continue
        a=dict(re.findall(r'(\w+)="([^"]*)"',line))
        vid=a.get('vehicle')
        if vid not in targets:continue
        typ=a['type']
        if typ=='charging_start':
            active[vid]={'vehicle_id':vid,'charger_id':a['charger'],'event_start':float(a['time']),'battery_start_kWh':None,'event_energy_kWh':0.0}
        elif typ=='energy_charged' and vid in active:
            r=active[vid];en=float(a['energy'])/3.6e6;end=float(a['endCharge'])/3.6e6
            if r['battery_start_kWh'] is None:r['battery_start_kWh']=end-en
            r['event_energy_kWh']+=en;r['battery_end_kWh']=end
        elif typ=='charging_end' and vid in active:
            r=active.pop(vid);r['event_end']=float(a['time']);events.append(r)
E=pd.DataFrame(events)
D=D.merge(E,left_on=['vehicle_id','charger_id','charging_start_time'],right_on=['vehicle_id','charger_id','event_start'],how='left',validate='m:1')
assert D.battery_start_kWh.notna().all()
assert np.allclose(D.energy_kWh,D.event_energy_kWh)
D['capacity_kWh']=D.vehicle_id.map(vehicles)
D['soc_start']=D.battery_start_kWh/D.capacity_kWh
D['soc_end']=D.battery_end_kWh/D.capacity_kWh
D.to_csv(OUT/'selected_sessions_with_battery.csv',index=False)
agg=D.groupby(['zone','location','hour']).agg(n=('person_id','size'),energy=('energy_kWh','sum'),mean=('energy_kWh','mean'),median=('energy_kWh','median'),soc_start=('soc_start','mean'),soc_end=('soc_end','mean'),duration_h=('duration_h','mean'))
agg.to_csv(OUT/'hourly.csv');print(agg.round(3).to_string())
mix=D.groupby(['zone','location','hour','dwelling']).agg(n=('person_id','size'),mean=('energy_kWh','mean'),soc_start=('soc_start','mean'),soc_end=('soc_end','mean'))
mix.to_csv(OUT/'dwelling_mix.csv')
# Symmetric exact decomposition: change in mean = within-group + composition.
o=D[(D.zone=='Outer')&D.hour.isin([8,11])]
a=o[o.hour==8].groupby('dwelling').energy_kWh.agg(['count','mean'])
b=o[o.hour==11].groupby('dwelling').energy_kWh.agg(['count','mean'])
pa=a['count']/a['count'].sum();pb=b['count']/b['count'].sum()
within=(((pa+pb)/2)*(b['mean']-a['mean'])).sum()
composition=(((a['mean']+b['mean'])/2)*(pb-pa)).sum()
stats={'outer_8_to_11_within_group_kWh':within,'outer_8_to_11_composition_kWh':composition}
t=pd.read_csv(HERE/'figures/Figure_06_zones_A_data.csv')
for z,l in [('Outer','Public'),('Inner','Home')]:
    d=t[(t.group==z)&(t.location==l)].set_index('hour').loc[6:22]
    x=np.linspace(6,22,400);y=np.maximum(make_interp_spline(d.index,d.per_starting_EV.fillna(0),k=3)(x),0)
    mask=(x>=7)&(x<=12)
    ix=np.flatnonzero(mask)[np.argmax(y[mask])]
    stats[z+'_spline_morning_peak']={'hour':float(x[ix]),'kWh':float(y[ix])}
(OUT/'summary.json').write_text(json.dumps(stats,indent=2));print(stats)
act=pd.read_csv(next(IT.glob('*.activities.csv.gz')),sep=';',dtype={'person':str})
ids=set(D[(D.zone=='Inner')&(D.hour==8)].person_id)
act=act[act.person.isin(ids)]
act.to_csv(OUT/'inner_8am_activities.csv',index=False)
print(act[['person','activity_number','activity_type','start_time','end_time']].to_string(index=False))
