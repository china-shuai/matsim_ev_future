"""Record prelaunch XML-format-only correction; no parameter values changed."""
import json,shutil,hashlib
from pathlib import Path
here=Path(__file__).resolve().parent
assert not (here/'run_status.json').exists()
assert not any((here/'outputs').iterdir())
shutil.copy2(here/'manifest.json',here/'manifest_before_xml_header_fix.json')
m=json.loads((here/'manifest.json').read_text())
for c in m['configs']:c['sha256']=hashlib.sha256(Path(c['path']).read_bytes()).hexdigest()
m['prelaunch_format_correction']='Added MATSim config_v2 DOCTYPE; config parameters unchanged.'
(here/'manifest.json').write_text(json.dumps(m,indent=2))
(here/'logs/smoke_owner_only.log').rename(here/'logs/smoke_config_header_failure.log')
