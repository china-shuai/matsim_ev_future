"""Independent exported-value checks and a preview of the eight scientific figures."""
from pathlib import Path
import json, zipfile
import numpy as np
import pandas as pd
from PIL import Image, ImageDraw

HERE=Path(__file__).resolve().parent
OUT=HERE/'figures'
RUN=HERE.parent/'mainline_owner_only_20260923'
checks=[]
def check(label,condition):
    assert condition,label
    checks.append(label)

daily=pd.read_csv(OUT/'daily_totals.csv')
curves=pd.read_csv(OUT/'Figure_08_data.csv')
for side in ['A','B']:
    name=f'{side}_aptHC{"56" if side=="A" else "95"}_houseHC95'
    raw=pd.read_csv(RUN/'outputs'/name/'ITERS/it.100'/f'{name}_ownerOnly.100.wevc_charging_attempts.csv',sep=';')
    raw=raw[raw.successful.eq(True)&raw.charged.eq(True)&raw.energy_kWh.gt(0)]
    valid=raw[np.isfinite(raw.charging_start_time)&np.isfinite(raw.charging_end_time)&raw.charging_end_time.gt(raw.charging_start_time)]
    s=pd.read_csv(OUT/f'sessions_{side}.csv')
    p=pd.read_csv(OUT/f'population_{side}_zones.csv')
    check(f'{side}: raw/export session count',len(valid)==len(s))
    check(f'{side}: raw/export total energy',np.isclose(valid.energy_kWh.sum(),s.energy_kWh.sum()))
    check(f'{side}: prefix/owner classification',s.charger_id.str.startswith('privateCharger_').equals(s.location.eq('Home')))
    for loc in ['Home','Public']:
        total=daily.query('scenario==@side and group=="ALL" and location==@loc').energy_kWh.iloc[0]
        check(f'{side}/{loc}: totals and subgroup sums',np.isclose(total,daily.query('scenario==@side and group!="ALL" and location==@loc').energy_kWh.sum()))
    for panel,loc,apt in [(0,'Public',False),(1,'Home',True),(2,'Public',True)]:
        d=s[s.location.eq(loc)&(s.dwelling.eq('APARTMENT') if apt else True)]
        exported=curves.query('scenario==@side and panel==@panel').sort_values('hour')
        # Independently calculate every bin by interval intersection, before cyclic folding.
        energies=[]
        starts=d.charging_start_time.to_numpy();ends=d.charging_end_time.to_numpy()
        rates=d.energy_kWh.to_numpy()/(ends-starts)
        for k in range(96):
            value=0.0
            for day in range(int(np.floor(starts.min()/86400)),int(np.floor(ends.max()/86400))+1):
                lo=day*86400+k*900;hi=lo+900
                overlap=np.maximum(0,np.minimum(ends,hi)-np.maximum(starts,lo))
                value+=float((rates*overlap).sum())
            energies.append(value)
        check(f'{side}/{panel}: independent 96-bin power',np.allclose(np.array(energies)*4,exported.power_kW))
        check(f'{side}/{panel}: integrated power equals energy',np.isclose(exported.power_kW.sum()/4,d.energy_kWh.sum()))
    if side=='A':
        for dim,f in [('dwelling','Figure_03_dwelling_A_data.csv'),('zone','Figure_06_zones_A_data.csv')]:
            table=pd.read_csv(OUT/f)
            for row in table.itertuples():
                d=s[s.location.eq(row.location)&s[dim].eq(row.group)&s.hour.eq(row.hour)]
                n=int(p[dim].eq(row.group).sum())
                check(f'{f}/{row.location}/{row.group}/{row.hour}',np.isclose(row.energy,d.energy_kWh.sum()) and np.isclose(row.per_fleet,d.energy_kWh.sum()/n) and row.cars==d.person_id.nunique())
        check('Sankey conserves public sessions',pd.read_csv(OUT/'Figure_07_flow_data.csv').sessions.sum()==s.location.eq('Public').sum())

files=sorted(OUT.glob('Figure_*.png'))
check('Eight figure exports',len(files)==8)
for f in files:
    check(f'{f.stem}: three formats',all(f.with_suffix('.'+ext).is_file() for ext in ['png','pdf','svg']))
canvas=Image.new('RGB',(1800,2400),'white');draw=ImageDraw.Draw(canvas)
for i,f in enumerate(files):
    im=Image.open(f).convert('RGB');im.thumbnail((870,550))
    x=(i%2)*900;y=(i//2)*600
    draw.text((x+20,y+10),f.stem,fill='black')
    canvas.paste(im,(x+(900-im.width)//2,y+40+(550-im.height)//2))
canvas.save(HERE/'All_figures_preview.png')
(HERE/'verification.json').write_text(json.dumps({'passed':True,'checks':checks,'count':len(checks)},indent=2))
with zipfile.ZipFile(HERE/'Main_figures_owner_only_20260924.zip','w',zipfile.ZIP_DEFLATED) as z:
    for f in sorted(OUT.iterdir()):
        if f.suffix in ['.png','.pdf','.svg'] or f.name in ['provenance.json','daily_totals.csv'] or f.name.endswith('_data.csv') or f.name.endswith('_cells.csv'):
            z.write(f,f.relative_to(HERE))
    for name in ['README.md','rebuild_figures.py','verify_and_package.py','verification.json','All_figures_preview.png']:
        z.write(HERE/name,name)
print(f'PASS: {len(checks)} checks; package ready.')
