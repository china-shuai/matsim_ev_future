"""Pinned A/B updates to manuscript Figures 3--10 using recovered source definitions.

Original scripts are read-only references. Uses completed owner-only A/B reruns.
"""
from pathlib import Path
import ast, gzip, hashlib, json, re
import xml.etree.ElementTree as ET
import numpy as np
import pandas as pd
import geopandas as gpd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap, BoundaryNorm, Normalize
from matplotlib.patches import PathPatch, Rectangle
from matplotlib.path import Path as MPath
from scipy.interpolate import make_interp_spline
from scipy.signal import savgol_filter

HERE=Path(__file__).resolve().parent
OUT=HERE/'figures'
ROOT=HERE.parents[1]
RUN=ROOT/'analysis/mainline_owner_only_20260923'
ORIG=Path('/Users/S4065267/PyCharmMiscProject/paper2')
LGA=Path('/Users/S4065267/Downloads/同步空间/A_Paper1/Data/LGA/LGA.shp')
CRS='EPSG:28355'
ZONES=['Inner','Middle','Outer']
GROUPS=['APARTMENT','HOUSE_NO_PV','HOUSE_WITH_PV']
LABELS=['Apartment','House without PV','House with PV']
GC=['#1f77b4','#ff7f0e','#2ca02c']
ZC=['#d62728','#1f77b4','#2ca02c']
QUALITY=[]; SOURCES=[]; GRID={}
plt.rcParams.update({'font.family':'Times New Roman','font.size':11,'axes.labelsize':12,
    'axes.spines.top':False,'axes.spines.right':False,'pdf.fonttype':42,'svg.fonttype':'none'})

def source(p):
    SOURCES.append({'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})

def save(fig,name):
    for ext in ['png','pdf','svg']:fig.savefig(OUT/f'{name}.{ext}',dpi=300,bbox_inches='tight')
    plt.close(fig)

def mapping():
    p=ORIG/'publicvspri/public_energy_change_by_zone_scenarios.py';source(p)
    for node in ast.parse(p.read_text()).body:
        if isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='LGA_TO_ZONE' for t in node.targets):
            return ast.literal_eval(node.value)
    raise ValueError('No source zone map')

def popload(path,lga):
    rows=[]
    for _,p in ET.iterparse(path,events=['end']):
        if p.tag!='person':continue
        attrs={a.get('name'):a.text for a in p.findall('./attributes/attribute')}
        acts=p.findall('./plan/activity');home=next(a for a in acts if a.get('type')=='Home')
        rows.append({'person_id':p.get('id'),'dwelling':attrs['ev:dwellingType'],
            'home_x':float(home.get('x')),'home_y':float(home.get('y'))})
        p.clear()
    df=pd.DataFrame(rows)
    geo=gpd.GeoDataFrame(df,geometry=gpd.points_from_xy(df.home_x,df.home_y),crs=CRS)
    j=gpd.sjoin(geo,lga[['zone','geometry']],how='left',predicate='within').drop(columns=['index_right','geometry'])
    assert not j.person_id.duplicated().any()
    assert len(j)==35628
    return pd.DataFrame(j)

def network(path):
    nodes={};links={}
    with gzip.open(path,'rb') as f:
        for _,e in ET.iterparse(f,events=['end']):
            if e.tag=='node':nodes[e.get('id')]=(float(e.get('x')),float(e.get('y')))
            elif e.tag=='link':
                a=nodes[e.get('from')];b=nodes[e.get('to')]
                links[e.get('id')]=((a[0]+b[0])/2,(a[1]+b[1])/2)
                e.clear()
    return links

def load(side,lga,links):
    name={'A':'A_aptHC56_houseHC95','B':'B_aptHC95_houseHC95'}[side]
    poppath=RUN/'inputs'/f'{"05" if side=="A" else "08"}_population_{name}.xml'
    chargerpath=RUN/'inputs'/f'{"03" if side=="A" else "07"}_chargers_{name}.xml'
    attemptpath=RUN/'outputs'/name/'ITERS/it.100'/f'{name}_ownerOnly.100.wevc_charging_attempts.csv'
    for p in [poppath,chargerpath,attemptpath]:source(p)
    pop=popload(poppath,lga)
    chargers={};owners=set()
    for c in ET.parse(chargerpath).getroot().findall('charger'):
        cid=c.get('id');chargers[cid]=links[c.get('link')]
        for a in c.findall('./attributes/attribute'):
            if a.get('name')=='sevc:persons':
                for pid in re.split(r'[,\s]+',(a.text or '').strip()):
                    if pid:owners.add((cid,pid))
    raw=pd.read_csv(attemptpath,sep=';',dtype={'person_id':str,'charger_id':str})
    pos=raw[raw.successful.eq(True)&raw.charged.eq(True)&raw.energy_kWh.gt(0)].copy()
    valid=np.isfinite(pos.charging_start_time)&np.isfinite(pos.charging_end_time)&(pos.charging_end_time>pos.charging_start_time)
    ss=pos[valid].merge(pop,on='person_id',how='left',validate='m:1')
    assert ss.dwelling.notna().all()
    ss['location']=['Home' if (c,p) in owners else 'Public' for c,p in zip(ss.charger_id,ss.person_id)]
    prefix=ss.charger_id.str.startswith('privateCharger_')
    assert (prefix==ss.location.eq('Home')).all(), 'Owner/prefix classification disagreement'
    xy=ss.charger_id.map(chargers);assert xy.notna().all()
    ss['x']=[a[0] for a in xy];ss['y']=[a[1] for a in xy]
    ss.loc[ss.location.eq('Home'),'x']=ss.loc[ss.location.eq('Home'),'home_x']
    ss.loc[ss.location.eq('Home'),'y']=ss.loc[ss.location.eq('Home'),'home_y']
    geo=gpd.GeoDataFrame(ss,geometry=gpd.points_from_xy(ss.x,ss.y),crs=CRS)
    j=gpd.sjoin(geo,lga[['zone','geometry']].rename(columns={'zone':'charging_zone'}),how='left',predicate='within')
    assert len(j)==len(ss)
    ss=pd.DataFrame(j.drop(columns=['geometry','index_right']))
    ss['hour']=(ss.charging_start_time%86400//3600).astype(int)
    QUALITY.append({'scenario':side,'population':len(pop),'unmapped_home_EV':int((~pop.zone.isin(ZONES)).sum()),
        'positive_successful_sessions':len(pos),'valid_sessions':len(ss),
        'excluded_energy_kWh':float(pos.loc[~valid,'energy_kWh'].sum()),
        'excluded_energy_pct':100*pos.loc[~valid,'energy_kWh'].sum()/pos.energy_kWh.sum(),
        'public_sessions_unknown_charging_zone':int((ss.location.eq('Public')&~ss.charging_zone.isin(ZONES)).sum())})
    pop.to_csv(OUT/f'population_{side}_zones.csv',index=False)
    ss.to_csv(OUT/f'sessions_{side}.csv',index=False)
    return pop,ss

def hourly_figure(pop,ss,dim,values,labels,colors,name):
    counts=pop.groupby(dim).size()
    rows=[];fig,axes=plt.subplots(2,3,figsize=(12.5,8))
    fig.subplots_adjust(left=.085,right=.99,bottom=.13,top=.95,hspace=.49,wspace=.34)
    for row,loc in enumerate(['Public','Home']):
        for v,label,col in zip(values,labels,colors):
            d=ss[ss.location.eq(loc)&ss[dim].eq(v)]
            a=d.groupby('hour').agg(energy=('energy_kWh','sum'),sessions=('person_id','size'),cars=('person_id','nunique')).reindex(range(24),fill_value=0)
            a['per_fleet']=a.energy/counts[v]
            a['per_starting_EV']=a.energy/a.cars.replace(0,np.nan)
            a['per_session']=a.energy/a.sessions.replace(0,np.nan)
            a['group']=v;a['location']=loc
            rows.append(a.reset_index())
            for j,key in enumerate(['energy','per_fleet','per_starting_EV']):
                ax=axes[row,j];x=np.arange(6,23);y=a.loc[x,key].to_numpy()
                # Original hourly grouping and cubic interpolation; do not fabricate zero means.
                if np.isfinite(y).all() and np.count_nonzero(y)>3:
                    xs=np.linspace(6,22,400);ys=np.maximum(make_interp_spline(x,y,k=3)(xs),0)
                    ax.plot(xs,ys,color=col,lw=1.8,ls=['-','--','-.'][values.index(v)],label=label)
                else:ax.plot(x,y,color=col,lw=1.8,ls=['-','--','-.'][values.index(v)],label=label)
                ax.plot(x,y,'o',color=col,ms=2.2)
                ax.set(xlim=(6,22),xticks=range(6,23,4),xlabel='Charging start hour',ylim=(0,None))
                ax.grid(alpha=.23,ls='--')
                ax.set_ylabel([f'{loc} session energy (kWh)',f'{loc} energy (kWh / fleet EV)',f'{loc} energy (kWh / starting EV)'][j])
                ax.set_title(f'({"abcdef"[row*3+j]}) '+['Total energy','Energy per fleet EV','Energy per starting EV'][j],fontsize=11)
    for ax in axes.flat:
        ymax=max(np.nanmax(line.get_ydata()) for line in ax.lines)
        ax.set_ylim(0,ymax*1.08)
    handles,lab=axes[0,0].get_legend_handles_labels();fig.legend(handles,lab,ncol=3,frameon=False,loc='lower center',bbox_to_anchor=(.5,.04))
    fig.text(.5,.01,'A baseline; iteration 100. Full-session energy grouped by start hour. Dots: hourly values; lines: cubic interpolation.',ha='center',fontsize=9)
    save(fig,name);pd.concat(rows).to_csv(OUT/f'{name}_data.csv',index=False)

def extent(df,pad):
    x0,x1=df.x.min(),df.x.max();y0,y1=df.y.min(),df.y.max()
    return (x0-(x1-x0)*pad,x1+(x1-x0)*pad,y0-(y1-y0)*pad,y1+(y1-y0)*pad)

def hexdata(df,ext):
    fig,ax=plt.subplots();h=ax.hexbin(df.x,df.y,C=df.energy_kWh,reduce_C_function=np.sum,gridsize=60,extent=ext,mincnt=1)
    vals=np.asarray(h.get_array());xy=np.asarray(h.get_offsets());plt.close(fig)
    assert np.isclose(vals.sum(),df.energy_kWh.sum()), 'Hexbin lost energy'
    return vals,xy

def maps(datasets,labels,boundary,name,quantile):
    joined=pd.concat(datasets);ext=extent(joined,.05 if quantile else .08)
    vals=[hexdata(d,ext)[0] for d in datasets]
    if quantile:
        combined,_=hexdata(joined,ext);bounds=np.unique(np.quantile(combined[combined>0],np.linspace(0,1,7)))
        cmap=ListedColormap(plt.get_cmap('OrRd')(np.linspace(.18,.92,len(bounds)-1)))
        norm=BoundaryNorm(bounds,cmap.N,clip=True)
    else:
        bounds=[0,float(np.quantile(np.concatenate(vals),.98))];cmap=plt.get_cmap('OrRd');norm=Normalize(*bounds,clip=True)
    GRID[name]={'extent':ext,'gridsize':60,'bounds':list(bounds),'quantile':quantile}
    fig,axes=plt.subplots(1,len(datasets),figsize=(12,5.3),squeeze=False)
    fig.subplots_adjust(left=.02,right=.86,bottom=.13,top=.92,wspace=.04)
    cax=fig.add_axes([.89,.23,.02,.55])
    for ax,d,label in zip(axes[0],datasets,labels):
        boundary.boundary.plot(ax=ax,color='#b5b5b5',linewidth=.4,zorder=0)
        h=ax.hexbin(d.x,d.y,C=d.energy_kWh,reduce_C_function=np.sum,gridsize=60,extent=ext,mincnt=1,cmap=cmap,norm=norm,linewidths=.15,edgecolors='#555555',zorder=2)
        ax.set(xlim=ext[:2],ylim=ext[2:]);ax.set_aspect('equal');ax.axis('off');ax.set_title(label,fontsize=12)
        y=ext[2]+(ext[3]-ext[2])*.045;x=ext[0]+(ext[1]-ext[0])*.06
        ax.plot([x,x+20000],[y,y],color='black',lw=1.5);ax.text(x+10000,y+(ext[3]-ext[2])*.02,'20 km',ha='center',fontsize=8)
        v,xy=hexdata(d,ext);pd.DataFrame({'x':xy[:,0],'y':xy[:,1],'energy_kWh':v}).to_csv(OUT/f'{name}_{labels.index(label)}_cells.csv',index=False)
    cb=fig.colorbar(h,cax=cax,extend='max' if not quantile else 'neither')
    if quantile:
        cb.set_ticks((bounds[:-1]+bounds[1:])/2);cb.set_ticklabels([f'Q{i+1}' for i in range(len(bounds)-1)])
        cb.set_label('Energy quantile class')
    else:cb.set_label('Public charging energy (kWh / cell)')
    fig.text(.5,.04,'Iteration 100. Shared hexagonal grid and colour scale; local LGA boundaries; GDA94 / MGA zone 55.',ha='center',fontsize=9)
    save(fig,name)

def flow(ss):
    d=ss[ss.location.eq('Public')&ss.zone.isin(ZONES)&ss.charging_zone.isin(ZONES)]
    agg=d.groupby(['zone','dwelling','charging_zone']).agg(sessions=('person_id','size'),energy_kWh=('energy_kWh','sum'))
    agg.to_csv(OUT/'Figure_07_flow_data.csv')
    left=[(z,g) for z in ZONES for g in GROUPS];N=len(d);scale=.69/N
    fig,ax=plt.subplots(figsize=(11,7));ax.set(xlim=(-.58,1.42),ylim=(-.07,1.05));ax.axis('off')
    lbase={};rbase={};cursor=1
    for z,g in left:
        n=int(((d.zone==z)&(d.dwelling==g)).sum());height=n*scale
        cursor-=height;lbase[z,g]=cursor
        ax.add_patch(Rectangle((0,cursor),.018,height,color=ZC[ZONES.index(z)]))
        ax.text(-.015,cursor+height/2,f'{z}: {LABELS[GROUPS.index(g)]}\n{n:,} sessions',ha='right',va='center',fontsize=9)
        cursor-=.035
    cursor=1
    for z in ZONES:
        n=int((d.charging_zone==z).sum());height=n*scale;cursor-=height;rbase[z]=cursor
        ax.add_patch(Rectangle((.98,cursor),.018,height,color=ZC[ZONES.index(z)]))
        ax.text(1.015,cursor+height/2,f'Charging in {z}\n{n:,} sessions',ha='left',va='center',fontsize=11)
        cursor-=.14
    for z,g in left:
        for cz in ZONES:
            if (z,g,cz) not in agg.index:continue
            h=agg.loc[(z,g,cz),'sessions']*scale;a=lbase[z,g];b=rbase[cz]
            verts=[(.018,a),(.4,a),(.6,b),(.98,b),(.98,b+h),(.6,b+h),(.4,a+h),(.018,a+h),(.018,a)]
            codes=[MPath.MOVETO,MPath.CURVE4,MPath.CURVE4,MPath.CURVE4,MPath.LINETO,MPath.CURVE4,MPath.CURVE4,MPath.CURVE4,MPath.CLOSEPOLY]
            ax.add_patch(PathPatch(MPath(verts,codes),facecolor=ZC[ZONES.index(z)],alpha=.3,lw=0))
            lbase[z,g]+=h;rbase[cz]+=h
    ax.text(.5,1.035,'Residential zone and dwelling type → public charging zone',ha='center',fontsize=13)
    ax.text(.5,-.055,f'A baseline; iteration 100. Ribbon widths represent session counts (n = {N:,}), not energy.',ha='center',fontsize=10)
    save(fig,'Figure_07_public_sessions_flow_A')

def load_curve(d):
    energy=np.zeros(96)
    for r in d.itertuples():
        start=r.charging_start_time;end=r.charging_end_time;c=start
        while c<end:
            stop=min(end,(np.floor(c/900)+1)*900)
            energy[int(c//900)%96]+=r.energy_kWh*(stop-c)/(end-start);c=stop
    assert np.isclose(energy.sum(),d.energy_kWh.sum())
    return energy*4

def policy(data):
    fig,axes=plt.subplots(1,3,figsize=(12,4.5));fig.subplots_adjust(left=.08,right=.99,bottom=.25,top=.88,wspace=.32)
    rows=[]
    for i,(loc,apt,title) in enumerate([('Public',False,'(a) All-EV public charging'),('Home',True,'(b) Apartment home charging'),('Public',True,'(c) Apartment public charging')]):
        for side,color,ls in [('A','#444444','--'),('B','#1f77b4','-')]:
            d=data[side][1];d=d[d.location.eq(loc)&(d.dwelling.eq('APARTMENT') if apt else True)]
            y=load_curve(d);smooth=np.maximum(savgol_filter(y,15,3,mode='wrap'),0)
            for b in range(96):rows.append({'panel':i,'scenario':side,'hour':(b+.5)/4,'power_kW':y[b],'smoothed_power_kW':smooth[b]})
            axes[i].plot((np.arange(96)+.5)/4,smooth,color=color,ls=ls,lw=1.8,label=f'{side}: apartment access '+('56%' if side=='A' else '95%'))
        axes[i].set(xlim=(6,22),xticks=range(6,23,4),ylim=(0,None),xlabel='Hour of day',ylabel='Charging power (kW)',title=title)
        axes[i].grid(alpha=.23)
    h,l=axes[0].get_legend_handles_labels();fig.legend(h,l,ncol=2,loc='lower center',bbox_to_anchor=(.5,.055),frameon=False)
    fig.text(.5,.015,'House access 95% in both runs. Iteration 100; 15-minute bins; Savitzky–Golay display smoothing (15 bins, order 3).',ha='center',fontsize=9)
    save(fig,'Figure_08_access_AB');pd.DataFrame(rows).to_csv(OUT/'Figure_08_data.csv',index=False)
    rows=[]
    for side,(pop,ss) in data.items():
        for z in ZONES:
            n=int(pop.zone.eq(z).sum());energy=ss.loc[ss.location.eq('Public')&ss.zone.eq(z),'energy_kWh'].sum()
            rows.append({'scenario':side,'zone':z,'fleet_EV':n,'public_kWh':energy,'kWh_per_EV':energy/n})
    frame=pd.DataFrame(rows);frame.to_csv(OUT/'Figure_10_data.csv',index=False)
    fig,ax=plt.subplots(figsize=(6.8,4.7))
    for i,z in enumerate(ZONES):
        vals=frame[frame.zone.eq(z)].set_index('scenario').loc[['A','B'],'kWh_per_EV']
        ax.plot([56,95],vals,marker=['o','s','^'][i],ls=['-','--','-.'][i],color=ZC[i],label=z+' Melbourne',lw=1.8)
        for x,v in zip([56,95],vals):ax.annotate(f'{v:.2f}',(x,v),xytext=(0,-15 if i==2 else 8),textcoords='offset points',ha='center',fontsize=9)
    ax.set(xlim=(50,101),xticks=[56,95],ylim=(0,None),xlabel='Apartment home charger access (%)',ylabel='Public energy (kWh / fleet EV / day)')
    ax.set_ylim(0,frame.kWh_per_EV.max()*1.15)
    ax.grid(alpha=.23);ax.legend(frameon=False);fig.subplots_adjust(bottom=.19)
    fig.text(.5,.025,'All residents, by home zone; houses at 95% access. A/B endpoints only.',ha='center',fontsize=9)
    save(fig,'Figure_10_public_energy_by_zone_AB')

def main():
    OUT.mkdir(exist_ok=True)
    for p in [ORIG/'dwelling type/figure 1.py',ORIG/'zone/figure1.py',ORIG/'hotmap2.py',ORIG/'dwelling type/quantile_hexbin_energy_by_dwelling.py',ORIG/'od_matrix_by_dwelling.py',ORIG/'HC/Figure1 1x3.py',ORIG/'HC/Public_demand_2scenarios_hexbin_APARTMENT.py']:source(p)
    m=mapping();lga=gpd.read_file(LGA).to_crs(CRS)
    for ext in ['.shp','.shx','.dbf','.prj']:
        if LGA.with_suffix(ext).exists():source(LGA.with_suffix(ext))
    lga['zone']=lga.lga_name.str.replace('(C)','',regex=False).str.replace('(Vic.)','',regex=False).str.strip().map(m)
    completion=RUN/'completion.json';source(completion)
    assert json.loads(completion.read_text())['passed'] is True
    net=RUN/'outputs/A_aptHC56_houseHC95/A_aptHC56_houseHC95_ownerOnly.output_network.xml.gz';source(net)
    links=network(net)
    data={s:load(s,lga,links) for s in ['A','B']}
    p,a=data['A'];pb,b=data['B']
    assert p[['person_id','dwelling','home_x','home_y']].equals(pb[['person_id','dwelling','home_x','home_y']])
    pd.crosstab(p.zone,p.dwelling).reindex(ZONES).to_csv(OUT/'Table_2_zone_dwelling_counts.csv')
    hourly_figure(p,a,'dwelling',GROUPS,LABELS,GC,'Figure_03_dwelling_A')
    hourly_figure(p,a,'zone',ZONES,[z+' Melbourne' for z in ZONES],ZC,'Figure_06_zones_A')
    for loc,num in [('Public','04'),('Home','05')]:
        maps([a[a.location.eq(loc)&a.dwelling.eq(g)] for g in GROUPS],LABELS,lga,f'Figure_{num}_{loc.lower()}_spatial_A',True)
    maps([d[d.location.eq('Public')&d.dwelling.eq('APARTMENT')] for d in [a,b]],['A: apartment access 56%','B: apartment access 95%'],lga,'Figure_09_apartment_public_AB',False)
    flow(a);policy(data)
    daily=[]
    for side,(pop,ss) in data.items():
        for g in ['ALL']+GROUPS:
            for loc in ['Public','Home']:
                d=ss[ss.location.eq(loc)&(ss.dwelling.eq(g) if g!='ALL' else True)]
                daily.append({'scenario':side,'group':g,'location':loc,'sessions':len(d),'energy_kWh':float(d.energy_kWh.sum())})
    pd.DataFrame(daily).to_csv(OUT/'daily_totals.csv',index=False)
    source(Path(__file__))
    (OUT/'provenance.json').write_text(json.dumps({'sources':SOURCES,'quality':QUALITY,'grids':GRID,'iteration':100},indent=2))
    print(json.dumps(QUALITY,indent=2));print(pd.crosstab(p.zone,p.dwelling));print('Completed eight figures:',OUT)

if __name__=='__main__':main()
