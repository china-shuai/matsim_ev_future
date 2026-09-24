from pathlib import Path
import zipfile
from PIL import Image,ImageDraw
from export_latex_ready import DEST as LATEX_READY, LATEX_NAMES

HERE=Path(__file__).resolve().parent
OUT=HERE/'original_style'
if not OUT.is_dir():
    OUT=HERE/'Original_style_all_figures_OSM_light'
files=sorted(OUT.glob('Figure_*.png'))
assert len(files)==8
canvas=Image.new('RGB',(1800,2400),'white');draw=ImageDraw.Draw(canvas)
for i,f in enumerate(files):
    im=Image.open(f).convert('RGB');im.thumbnail((870,540))
    x=(i%2)*900;y=(i//2)*600
    draw.text((x+20,y+10),f.stem,fill='black')
    canvas.paste(im,(x+(900-im.width)//2,y+40+(540-im.height)//2))
canvas.save(OUT/'Ready_figures_preview.png')
with zipfile.ZipFile(HERE/'Original_style_all_figures_OSM_light.zip','w',zipfile.ZIP_DEFLATED) as z:
    for f in OUT.iterdir():
        if f.is_file() and (f.name.startswith('Figure_') or f.name in ['README.md','Ready_figures_preview.png']):
            z.write(f,f.name)
    snapshots=OUT/'source_snapshots'
    if not snapshots.is_dir():
        snapshots=HERE/'Original_style_all_figures_OSM_light'/'source_snapshots'
    for f in snapshots.rglob('*.py'):
        z.write(f,Path('source_snapshots')/f.relative_to(snapshots))
    z.write(HERE/'restore_original_style.py','restore_original_style.py')
    for f in (HERE/'figures').glob('*_data.csv'):
        z.write(f,'data/'+f.name)
    for name in LATEX_NAMES:
        z.write(LATEX_READY/name,'latex_ready_original_names/'+name)
print('Eight figures packaged with exact-name LaTeX copies; superseded watermarked map drafts excluded.')
