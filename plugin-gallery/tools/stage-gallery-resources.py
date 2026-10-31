#!/usr/bin/env python3
"""Deploy public placeholder-only Gallery templates. Explicit --apply; never overwrite collisions."""
from pathlib import Path
import argparse,hashlib,json,shlex,subprocess,tarfile,tempfile
ROOT=Path(__file__).resolve().parent.parent/'src/main/assets/gallery-halfpage'
PINS={'com.miui.home':'77d119ca63fc957f6bf7cbecbfaa19fe54ec1c68bdf061a23becfe04b2fe62ec','com.miui.gallery':'a2c12d04bcaf1b90497c049cffa8d83b16bf229eb932232f31f8825f6f130199','com.miui.personalassistant':'277c68b24ecf4df442aef395277e166a5703001af8409d24bc52cbc7051f725d'}
a=argparse.ArgumentParser();a.add_argument('--serial',required=True);a.add_argument('--apply',action='store_true');opt=a.parse_args()
rows=json.loads((ROOT/'payload-index.json').read_text());q=shlex.quote
print(json.dumps({'mode':'apply' if opt.apply else 'plan','resources':'public loading-glyph placeholders; no user photos','products':[r['id'] for r in rows],'overwrites':False,'database_writes':False,'restart':False},indent=2))
if not opt.apply:raise SystemExit(0)
def adb(*args):return subprocess.check_output(['adb','-s',opt.serial,*args],text=True).strip()
def su(s):return adb('shell','su -M -c '+q(s))
for pkg,pin in PINS.items():
 apk=adb('shell','pm','path',pkg).split('package:',1)[1].splitlines()[0]
 if su('sha256sum '+q(apk)).split()[0]!=pin:raise SystemExit('ROM package mismatch: '+pkg)
entries=[]
for row in rows:
 for key,digest in [('widget','sha256'),('archive','archive_sha256')]:
  if hashlib.sha256((ROOT/row[key]).read_bytes()).hexdigest()!=row[digest]:raise SystemExit('Public payload SHA mismatch')
for p in (ROOT/'payloads').rglob('*'):
 if p.is_file() and p.name not in ('description.xml','meta.json'):
  entries.append((p,p.relative_to(ROOT/'payloads').as_posix(),hashlib.sha256(p.read_bytes()).hexdigest()))
home='/data/user_de/0/com.miui.home/files/maml/res/0';pa='/data/user/0/com.miui.personalassistant/files';dest=pa+'/duo-gallery-halfpage-v3/resources'
checks=['set -eu','test "$(id -u)" = 0']
for r in rows:
 base=home+'/'+r['id']+'/'+str(r['version'])+'/'+r['id']
 checks+=['test -f '+q(base+'.right'),'test -f '+q(base+'.zip')]
# Preflight all destination collisions before any resource write.
for _,rel,sha in entries:
 for base in (home,dest):
  t=base+'/'+rel
  checks+=['test ! -L '+q(t),'if [ -e '+q(t)+' ]; then test -f '+q(t)+'; test "$(sha256sum '+q(t)+' | cut -d " " -f1)" = '+q(sha)+'; fi']
su('\n'.join(checks))
stage=su('mktemp -d /data/local/tmp/gallery-public.XXXXXX')
su('chown 2000:2000 '+q(stage)+'; chmod 700 '+q(stage))
with tempfile.TemporaryDirectory() as td:
 bundle=Path(td)/'public.tar'
 with tarfile.open(bundle,'w') as t:
  for p,rel,_ in entries:t.add(p,arcname=rel)
 adb('push',str(bundle),stage+'/public.tar')
 script=['set -eu','home_uid=$(stat -c %u /data/user_de/0/com.miui.home)','pa_uid=$(stat -c %u /data/user/0/com.miui.personalassistant)','mkdir '+q(stage+'/unpack'),'tar -xf '+q(stage+'/public.tar')+' -C '+q(stage+'/unpack')]
 for _,rel,_ in entries:
  for base,uid in ((home,'$home_uid'),(dest,'$pa_uid')):
   target=base+'/'+rel
   script+=['if [ ! -e '+q(target)+' ]; then mkdir -p '+q(str(Path(target).parent))+'; cp '+q(stage+'/unpack/'+rel)+' '+q(target)+'; chown '+uid+':'+uid+' '+q(target)+'; chmod 600 '+q(target)+'; fi']
 for folder,uid in ((dest,'$pa_uid'),):
  script+=['chown -R '+uid+':'+uid+' '+q(folder),'find '+q(folder)+' -type d -exec chmod 700 {} \\;']
 ready=json.dumps({'schema':1,'enabled':True,'span':'4x6','home_apk_sha256':PINS['com.miui.home'],'gallery_apk_sha256':PINS['com.miui.gallery'],'pa_apk_sha256':PINS['com.miui.personalassistant']},separators=(',',':'))
 marker=pa+'/duo-gallery-halfpage-v1/ready.json';protocol=pa+'/duo-gallery-halfpage-v3/protocol-ready'
 script+=['mkdir -p '+q(str(Path(marker).parent)), 'printf %s '+q(ready)+' > '+q(marker),'printf %s DUO_GALLERY_NATIVE_23_V3 > '+q(protocol),'chown -R "$pa_uid:$pa_uid" '+q(pa+'/duo-gallery-halfpage-v1')+' '+q(pa+'/duo-gallery-halfpage-v3'),'chmod 600 '+q(marker)+' '+q(protocol),'restorecon -RF '+q(pa+'/duo-gallery-halfpage-v1')+' '+q(pa+'/duo-gallery-halfpage-v3'),'restorecon -RF '+q(home),'echo PUBLIC_TEMPLATE_RESOURCES_STAGED']
 print(su('\n'.join(script)))
