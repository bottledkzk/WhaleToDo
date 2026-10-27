"""Build the static site with exactly the four verified preview attachments."""
import argparse, hashlib, json, subprocess, zipfile
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('artifact_directory',type=Path)
args=parser.parse_args()
repo=Path(__file__).resolve().parent.parent
def git(*args):return subprocess.check_output(['git','-C',str(repo),*args])
if git('status','--porcelain','--untracked-files=normal'):raise SystemExit('COMMIT_SOURCE_FIRST')
artifact=args.artifact_directory.resolve()
meta=json.loads((artifact/'release.json').read_text(encoding='utf-8-sig'))
if meta['tag']!='v'+meta['version'] or meta['package']!='io.whaletodo.app.preview':raise SystemExit('INVALID_PREVIEW_MANIFEST')
apk=artifact/meta['asset']
if hashlib.sha256(apk.read_bytes()).hexdigest()!=meta['sha256']:raise SystemExit('APK_HASH_MISMATCH')
dest=artifact/('WhaleToDo-website-'+meta['tag']+'.zip')
checksum=dest.with_suffix(dest.suffix+'.sha256')
if dest.exists() or checksum.exists():raise SystemExit('WEBSITE_PACKAGE_ALREADY_EXISTS')
files={}
for name in git('ls-tree','-r','--name-only','HEAD','web').decode().splitlines():
    if name.startswith('web/') and not name.startswith(('web/downloads/','web/dist/')):
        files[name[4:]]=git('show','HEAD:'+name)
for name in [meta['asset'],'SHA256SUMS.txt','release.json','RELEASE-NOTES.md']:
    files['downloads/'+meta['tag']+'/'+name]=(artifact/name).read_bytes()
manifest={'website_source_commit':git('rev-parse','HEAD').decode().strip(),
          'apk_source_commit':meta['source_commit'],'version':meta['version'],
          'deployed':False,'files':{n:hashlib.sha256(b).hexdigest() for n,b in sorted(files.items())}}
files['site-manifest.json']=(json.dumps(manifest,indent=2)+'\n').encode()
with zipfile.ZipFile(dest,'w',compression=zipfile.ZIP_DEFLATED) as archive:
    for name,data in sorted(files.items()):
        info=zipfile.ZipInfo(name);info.compress_type=zipfile.ZIP_DEFLATED
        archive.writestr(info,data)
with zipfile.ZipFile(dest) as archive:
    if archive.testzip() is not None:raise SystemExit('WEBSITE_ARCHIVE_INVALID')
    for name,digest in manifest['files'].items():
        if hashlib.sha256(archive.read(name)).hexdigest()!=digest:raise SystemExit('WEBSITE_READBACK_MISMATCH')
digest=hashlib.sha256(dest.read_bytes()).hexdigest()
checksum.write_text(digest+'  '+dest.name+'\n',encoding='ascii')
print(json.dumps({'file':str(dest),'sha256':digest,'members':len(files),'deployed':False}))
