"""Build the static site with exactly the four verified preview attachments."""
import argparse, hashlib, json, re, subprocess, zipfile
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('artifact_directory',type=Path)
parser.add_argument('--public-release-tag', help='Use this fallback only after verifying the matching GitHub release is public')
args=parser.parse_args()
repo=Path(__file__).resolve().parent.parent
def git(*args):return subprocess.check_output(['git','-C',str(repo),*args])
if git('status','--porcelain','--untracked-files=normal'):raise SystemExit('COMMIT_SOURCE_FIRST')
artifact=args.artifact_directory.resolve()
meta=json.loads((artifact/'release.json').read_text(encoding='utf-8-sig'))
if (not re.fullmatch(r'\d+\.\d+\.\d+-preview\.\d+',meta['version']) or meta['tag']!='v'+meta['version'] or
    meta['package']!='io.whaletodo.app.preview' or meta['asset']!='WhaleToDo-'+meta['version']+'.apk' or
    not re.fullmatch(r'[a-f0-9]{40}',meta['source_commit']) or not re.fullmatch(r'[a-f0-9]{64}',meta['sha256']) or
    type(meta['version_code']) is not int or meta['version_code']<1):raise SystemExit('INVALID_PREVIEW_MANIFEST')
pin=git('show','HEAD:release/preview-certificate.sha256').decode().strip()
if meta.get('certificate_sha256')!=pin:raise SystemExit('PREVIEW_CERTIFICATE_MISMATCH')
if args.public_release_tag is not None and args.public_release_tag!=meta['tag']:
    raise SystemExit('PUBLIC_RELEASE_TAG_MISMATCH')
apk=artifact/meta['asset']
if hashlib.sha256(apk.read_bytes()).hexdigest()!=meta['sha256']:raise SystemExit('APK_HASH_MISMATCH')
dest=artifact/('WhaleToDo-website-'+meta['tag']+'.zip')
checksum=dest.with_suffix(dest.suffix+'.sha256')
if dest.exists() or checksum.exists():raise SystemExit('WEBSITE_PACKAGE_ALREADY_EXISTS')
files={}
for name in git('ls-tree','-r','--name-only','HEAD','web').decode().splitlines():
    if name.startswith('web/') and not name.startswith(('web/downloads/','web/dist/')):
        files[name[4:]]=git('show','HEAD:'+name)
# The checked-in page keeps pointing at the existing public release. Only the staged
# website package is rendered for this newly signed artifact, avoiding nonexistent live links.
template=files['index.html'].decode('utf-8')
versions=set(re.findall(r'\d+\.\d+\.\d+-preview\.\d+',template))
if len(versions)!=1:raise SystemExit('WEBSITE_TEMPLATE_VERSION_AMBIGUOUS')
old_version=versions.pop()
for name in ['index.html','app.js']:
    # Keep the verified public fallback tag until the candidate release actually exists.
    public_tag='https://github.com/bottledkzk/WhaleToDo/releases/tag/v'+old_version
    text=files[name].decode('utf-8').replace(public_tag,'__VERIFIED_PUBLIC_RELEASE__').replace(old_version,meta['version'])
    fallback='https://github.com/bottledkzk/WhaleToDo/releases/tag/'+args.public_release_tag if args.public_release_tag else public_tag
    text=text.replace('__VERIFIED_PUBLIC_RELEASE__',fallback)
    if name=='index.html':
        text=re.sub(r' · code \d+', ' · code '+str(meta['version_code']),text)
        if meta.get('online_account_agent')=='client_integrated_production_acceptance_pending':
            text=text.replace('APP 账户与在线 AI 尚待接入。','客户端已接入账户与 AI，生产联调尚待验收。')
            text=text.replace('当前安装包尚未接通账户和在线 AI。','客户端已接入账户与 AI，生产联调尚待验收。')
            text=text.replace('日记界面 · 在线生成待接入','日记界面 · 在线生成待生产验收')
    files[name]=text.encode('utf-8')
for name in [meta['asset'],'SHA256SUMS.txt','release.json','RELEASE-NOTES.md']:
    files['downloads/'+meta['tag']+'/'+name]=(artifact/name).read_bytes()
manifest={'website_source_commit':git('rev-parse','HEAD').decode().strip(),
          'apk_source_commit':meta['source_commit'],'version':meta['version'],
          'public_release_tag':args.public_release_tag,
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
