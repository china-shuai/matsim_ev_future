"""Isolated owner-only fix deployment and A/B rerun; never overwrite previous results.

prepare -> test -> smoke -> launch. The supervisor records exit codes and ownership audits.
"""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, time
import xml.etree.ElementTree as ET
import csv

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
REPO=ROOT.parent/'matsim-libs-main'
OLD=ROOT/'analysis/full_population_hc_scenarios_20260921'
ENGINE=REPO/'contribs/ev/src/main/java/org/matsim/contrib/ev/withinday/WithinDayEvEngine.java'
TEST=REPO/'contribs/ev/src/test/java/org/matsim/contrib/ev/withinday/PrivateChargerAccessRegression.java'
RUNNER=ROOT/'analysis/baseline_validation/RunValidationScenario.java'
CP=(ROOT/'analysis/baseline_validation/runtime_classpath.txt').read_text().strip()
NAMES=['A_aptHC56_houseHC95','B_aptHC95_houseHC95']

def sha(p):
    h=hashlib.sha256()
    with Path(p).open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

def write_json(path,data):
    tmp=path.with_suffix('.pending');tmp.write_text(json.dumps(data,indent=2));tmp.replace(path)

def put(root,module,key,value):
    m=root.find(f"module[@name='{module}']");e=m.find(f"param[@name='{key}']")
    if e is None:e=ET.SubElement(m,'param',{'name':key})
    e.set('value',str(value))

def prepare():
    if (HERE/'manifest.json').exists():raise RuntimeError('Already prepared; refusing to replace frozen inputs/runtime')
    for d in ['inputs','configs','sources','classes','runtime','logs','outputs']: (HERE/d).mkdir(exist_ok=True)
    for src in [ENGINE,TEST,RUNNER]:shutil.copy2(src,HERE/'sources'/src.name)
    subprocess.run(['javac','-cp',CP,'-d',str(HERE/'classes')]+[str(HERE/'sources'/p.name) for p in [ENGINE,TEST,RUNNER]],check=True)
    oldjar=next(Path(p) for p in CP.split(':') if '/org/matsim/contrib/ev/' in p and p.endswith('.jar'))
    fixed=HERE/'runtime/ev-owner-only.jar';shutil.copy2(oldjar,fixed)
    subprocess.run(['jar','--update','--file',str(fixed),'-C',str(HERE/'classes'),'org/matsim/contrib/ev/withinday'],check=True)
    fixedcp=':'.join(str(fixed) if Path(p)==oldjar else p for p in CP.split(':'))+':'+str(HERE/'classes')
    (HERE/'runtime/classpath.txt').write_text(fixedcp)
    subprocess.run(['git','-C',str(REPO),'diff','--','contribs/ev/src/main/java/org/matsim/contrib/ev/withinday/WithinDayEvEngine.java'],check=True,stdout=(HERE/'sources/owner_only.patch').open('w'))
    # Resolve and freeze every non-null file input from each config, not just the charger file.
    copies={};configs=[]
    for name,template,last in [(n,OLD/'configs'/f'config_{n}.xml',100) for n in NAMES]+[('smoke_owner_only',ROOT/'output_baseline/baseline.output_config.xml',2)]:
        tree=ET.parse(template);r=tree.getroot()
        for e in r.findall('./module/param'):
            val=e.get('value','');src=Path(val)
            if not src.is_absolute() or not src.is_file():continue
            if src not in copies:
                dest=HERE/'inputs'/f'{len(copies):02d}_{src.name}'
                shutil.copy2(src,dest);assert sha(src)==sha(dest)
                copies[src]=dest
            e.set('value',str(copies[src]))
        put(r,'controller','outputDirectory',HERE/'outputs'/name)
        put(r,'controller','runId',name+'_ownerOnly')
        put(r,'controller','lastIteration',last)
        put(r,'controller','overwriteFiles','failIfDirectoryExists')
        # Keep original seed, physical charger assignments, SOC, preferences and initial-home rule.
        path=HERE/'configs'/f'{name}.xml';ET.indent(tree)
        path.write_text('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE config SYSTEM "http://www.matsim.org/files/dtd/config_v2.dtd">\n'+ET.tostring(r,encoding='unicode'))
        configs.append({'name':name,'path':str(path),'sha256':sha(path),'source_config':str(template),'last_iteration':last})
    manifest={'created':time.strftime('%Y-%m-%dT%H:%M:%S%z'),'purpose':'Fix owner-only private charger access; rerun full-population A/B only',
        'old_ev_jar':{'path':str(oldjar),'sha256':sha(oldjar)},'fixed_ev_jar':{'path':str(fixed),'sha256':sha(fixed)},
        'sources':[{'path':str(HERE/'sources'/p.name),'sha256':sha(HERE/'sources'/p.name)} for p in [ENGINE,TEST,RUNNER]],
        'inputs':[{'original':str(src),'snapshot':str(dest),'sha256':sha(dest)} for src,dest in copies.items()],
        'configs':configs,'runtime':[{'path':p,'sha256':sha(p)} for p in fixedcp.split(':') if Path(p).is_file()]}
    write_json(HERE/'manifest.json',manifest)
    print('Prepared frozen sources, inputs and isolated fixed jar',flush=True)

def test():
    klass='org.matsim.contrib.ev.withinday.PrivateChargerAccessRegression'
    commands=[['java','-DexpectBug=true','-cp',CP+':'+str(HERE/'classes'),klass],
              ['java','-cp',(HERE/'runtime/classpath.txt').read_text(),klass]]
    for name,cmd in zip(['old_bug_reproduced','fixed_regression'],commands):
        result=subprocess.run(cmd,capture_output=True,text=True)
        (HERE/'logs'/f'{name}.log').write_text(result.stdout+result.stderr)
        print(result.stdout,flush=True)
        if result.returncode:raise RuntimeError(name+' failed: '+result.stderr)
    write_json(HERE/'unit_tests.json',{'passed':True,'old_bug_reproduced':True,'fixed_checks':14,'finished':time.time()})

def audit(name):
    cfg=ET.parse(HERE/'configs'/f'{name}.xml')
    charger=Path(cfg.find("./module[@name='ev']/param[@name='chargersFile']").get('value'))
    owners={};public=set()
    for c in ET.parse(charger).getroot():
        cid=c.get('id');attrs={a.get('name'):a.text for a in c.findall('./attributes/attribute')}
        owners[cid]={s.strip() for s in (attrs.get('sevc:persons') or '').split(',') if s.strip()}
        if (attrs.get('sevc:public') or '').lower()=='true':public.add(cid)
    rows=[]
    for p in sorted((HERE/'outputs'/name/'ITERS').glob('it.*/*.wevc_charging_attempts.csv')):
        positive=0;bad=0;private=0;pub=0
        with p.open() as f:
            for r in csv.DictReader(f,delimiter=';'):
                if r['charged'].lower()!='true' or not float(r['energy_kWh'])>0:continue
                positive+=1;cid=r['charger_id']
                isprivate=bool(owners.get(cid)) or cid.startswith('privateCharger_')
                private+=isprivate;pub+=cid in public
                if isprivate and r['person_id'] not in owners.get(cid,set()):bad+=1
        rows.append({'iteration':int(p.parent.name.split('.')[1]),'positive_sessions':positive,'private_sessions':private,'public_sessions':pub,'non_owner_sessions':bad})
    result={'run':name,'iterations':rows,'violations':sum(r['non_owner_sessions'] for r in rows)}
    write_json(HERE/f'{name}_ownership_audit.json',result)
    if not rows or result['violations']:raise RuntimeError('Missing or failed ownership audit: '+name)
    return result

def command(name,heap):
    return ['java',f'-Xmx{heap}','-Djava.awt.headless=true','-cp',(HERE/'runtime/classpath.txt').read_text(),
            'RunValidationScenario',str(HERE/'configs'/f'{name}.xml')]

def smoke():
    assert json.loads((HERE/'unit_tests.json').read_text())['passed']
    name='smoke_owner_only'
    if (HERE/'outputs'/name).exists():raise RuntimeError('Smoke output exists')
    with (HERE/'logs'/f'{name}.log').open('x') as f:
        result=subprocess.run(command(name,'4g'),cwd=ROOT,stdout=f,stderr=subprocess.STDOUT)
    if result.returncode:raise RuntimeError('Smoke failed; inspect log')
    result=audit(name)
    assert {r['iteration'] for r in result['iterations']}=={0,1,2}
    assert sum(r['private_sessions'] for r in result['iterations'])>0
    assert sum(r['public_sessions'] for r in result['iterations'])>0
    write_json(HERE/'smoke_passed.json',{'passed':True,'finished':time.time(),'audit':result})
    print(json.dumps(result,indent=2),flush=True)

def validate_frozen():
    m=json.loads((HERE/'manifest.json').read_text())
    for item in m['inputs']:assert sha(item['snapshot'])==item['sha256'],item['snapshot']
    for item in m['runtime']+m['configs']+m['sources']:assert sha(item['path'])==item['sha256'],item['path']

def supervise():
    validate_frozen()
    assert json.loads((HERE/'smoke_passed.json').read_text())['passed']
    procs=[];states=[]
    for name in NAMES:
        if (HERE/'outputs'/name).exists():raise RuntimeError('Refusing to overwrite '+name)
    for name in NAMES:
        f=(HERE/'logs'/f'{name}.log').open('x');cmd=command(name,'8g')
        p=subprocess.Popen(cmd,cwd=ROOT,stdout=f,stderr=subprocess.STDOUT)
        procs.append((p,f));states.append({'name':name,'pid':p.pid,'started':time.time(),'exit_code':None,'command':cmd})
    while True:
        for s,(p,f) in zip(states,procs):s['exit_code']=p.poll()
        write_json(HERE/'run_status.json',{'updated':time.time(),'runs':states})
        if all(s['exit_code'] is not None for s in states):break
        time.sleep(15)
    for p,f in procs:f.close()
    audits=[]
    for s in states:
        if s['exit_code']==0:audits.append(audit(s['name']))
    passed=len(audits)==2 and all(any(r['iteration']==100 for r in a['iterations']) for a in audits)
    write_json(HERE/'completion.json',{'passed':passed,'finished':time.time(),'runs':states,'ownership_audits':audits})

def launch():
    validate_frozen();assert json.loads((HERE/'smoke_passed.json').read_text())['passed']
    if (HERE/'supervisor.json').exists():raise RuntimeError('Already launched')
    with (HERE/'logs/supervisor.log').open('x') as f:
        p=subprocess.Popen([os.sys.executable,str(Path(__file__).resolve()),'supervise'],cwd=ROOT,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
    write_json(HERE/'supervisor.json',{'pid':p.pid,'started':time.time()});print('Supervisor started',p.pid,flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['prepare','test','smoke','launch','supervise'])
    globals()[parser.parse_args().action]()
