from pathlib import Path
import json,xml.etree.ElementTree as ET
import numpy as np
import pandas as pd

HERE=Path(__file__).resolve().parent;OUT=HERE/'figure6_diagnostic'
RUN=HERE.parent/'mainline_owner_only_20260923'
IT=RUN/'outputs/A_aptHC56_houseHC95/ITERS/it.100'
s=pd.read_csv(OUT/'selected_sessions_with_battery.csv',dtype={'person_id':str})
s=s[(s.zone=='Outer')&s.hour.isin([8,11,12])].copy()
all_sessions=pd.read_csv(HERE/'figures/sessions_A.csv',dtype={'person_id':str})
t=pd.read_csv(next(IT.glob('*.trips.csv.gz')),sep=';',dtype={'person':str})
l=pd.read_csv(next(IT.glob('*.legs.csv.gz')),sep=';',dtype={'person':str})
for df in [t,l]:
    df['dep_s']=pd.to_timedelta(df.dep_time).dt.total_seconds()
    df['arr_s']=df.dep_s+pd.to_timedelta(df.trav_time).dt.total_seconds()
    df.drop(df[~df.person.isin(s.person_id)].index,inplace=True)
leg_count_before=len(l)
l=l.drop_duplicates(subset=['person','trip_id','dep_s','arr_s','distance','mode','start_link','end_link'])
print('Duplicate leg rows removed:',leg_count_before-len(l))
# Reconcile deduplicated car-leg lengths to car-trip lengths, allowing metre rounding.
leg_totals=l[l['mode'].eq('car')].groupby('trip_id').distance.sum()
car_trips=t[t.main_mode.eq('car')].set_index('trip_id')
delta=leg_totals-car_trips.traveled_distance
print('Distance reconciliation: abs error median/max metres',delta.abs().median(),delta.abs().max())
root=ET.parse(RUN/'inputs/06_ev_vehicles_random_soc.xml').getroot()
initial={}
for v in root:
    if v.tag.endswith('vehicle'):
        a=next((a for a in v.iter() if a.get('name')=='initialSoc'),None)
        if a is not None:initial[v.get('id')]=float(a.text)
rows=[]
for r in s.itertuples():
    tr=t[t.person.eq(r.person_id)].sort_values('dep_s')
    legs=l[l.person.eq(r.person_id)&l['mode'].eq('car')]
    before=legs[legs.arr_s<=r.charging_start_time+1]
    during=tr[(tr.dep_s<=r.charging_start_time)&(tr.arr_s>=r.charging_start_time)]
    prior_charging=all_sessions[all_sessions.person_id.eq(r.person_id)&all_sessions.charging_end_time.le(r.charging_start_time)]
    row={'person_id':r.person_id,'hour':r.hour,'charging_zone':r.charging_zone,
         'soc_start':r.soc_start,'input_initial_soc':initial.get(r.person_id),
         'energy_kWh':r.energy_kWh,'completed_car_km_before_charge':before.distance.sum()/1000,
         'completed_car_legs':len(before),'prior_charging_sessions':len(prior_charging),
         'prior_charged_kWh':prior_charging.energy_kWh.sum(),
         'associated_trip_matches':len(during),
         'associated_destination':during.iloc[0].end_activity_type if len(during)==1 else 'UNRESOLVED',
         'activity_chain_to_charge':' → '.join([tr.iloc[0].start_activity_type]+tr[tr.dep_s<=r.charging_start_time].end_activity_type.tolist())}
    rows.append(row)
d=pd.DataFrame(rows);d.to_csv(OUT/'outer_trip_trace.csv',index=False)
summary=d.groupby('hour').agg(n=('person_id','size'),mean_km=('completed_car_km_before_charge','mean'),median_km=('completed_car_km_before_charge','median'),initial_soc=('input_initial_soc','mean'),charge_soc=('soc_start','mean'),prior_charging=('prior_charging_sessions','sum'))
print(summary.to_string())
print(pd.crosstab([d.hour,d.charging_zone],d.associated_destination).to_string())
print('matches',d.associated_trip_matches.value_counts().to_dict())
print(d[d.hour.eq(11)][['person_id','charging_zone','associated_destination','completed_car_km_before_charge','input_initial_soc','soc_start','activity_chain_to_charge']].head(10).to_string(index=False))
summary.to_csv(OUT/'outer_trip_hour_summary.csv')
pd.crosstab([d.hour,d.charging_zone],d.associated_destination).to_csv(OUT/'outer_trip_destinations.csv')
