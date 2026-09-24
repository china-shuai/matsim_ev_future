from pathlib import Path
import gzip,re,xml.etree.ElementTree as ET
import pandas as pd
HERE=Path(__file__).resolve().parent
OUT=HERE/'figure6_diagnostic'
RUN=HERE.parent/'mainline_owner_only_20260923'
IT=RUN/'outputs/A_aptHC56_houseHC95/ITERS/it.100'
s=pd.read_csv(OUT/'selected_sessions_with_battery.csv',dtype={'vehicle_id':str})
s=s[(s.zone=='Outer')&s.hour.isin([8,11,12])].copy()
cut=s.set_index('vehicle_id').charging_start_time.to_dict()
lengths={}
net=RUN/'outputs/A_aptHC56_houseHC95/A_aptHC56_houseHC95_ownerOnly.output_network.xml.gz'
with gzip.open(net,'rb') as h:
    for _,e in ET.iterparse(h,events=['end']):
        if e.tag=='link':lengths[e.get('id')]=float(e.get('length'));e.clear()
totals={v:{'distance_m':0.,'drive_kWh':0.,'initial_kWh':None} for v in cut}
with gzip.open(next(IT.glob('*.events.xml.gz')),'rt') as h:
    for line in h:
        if 'type="drivingEnergyConsumption"' not in line:continue
        a=dict(re.findall(r'(\w+)="([^"]*)"',line));time=float(a['time'])
        if time>max(cut.values()):break
        v=a['vehicle']
        if v not in cut or time>cut[v]:continue
        r=totals[v];energy=float(a['energy'])/3.6e6
        if r['initial_kWh'] is None:r['initial_kWh']=float(a['endCharge'])/3.6e6+energy
        r['drive_kWh']+=energy;r['distance_m']+=lengths[a['link']]
d=pd.DataFrame.from_dict(totals,orient='index').rename_axis('vehicle_id').reset_index()
s=s.merge(d,on='vehicle_id',validate='1:1')
s['balance_error_kWh']=s.initial_kWh-s.drive_kWh-s.battery_start_kWh
s['drive_km']=s.distance_m/1000
s['actual_initial_soc']=s.initial_kWh/s.capacity_kWh
s.to_csv(OUT/'outer_event_driving.csv',index=False)
print('max battery balance error kWh',s.balance_error_kWh.abs().max())
print(s.groupby('hour').agg(n=('vehicle_id','size'),km=('drive_km','mean'),median_km=('drive_km','median'),drive_kWh=('drive_kWh','mean'),initial_soc=('actual_initial_soc','mean'),start_soc=('soc_start','mean')).to_string())
