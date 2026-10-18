#!/usr/bin/env node
/** Release preparation only. Never imports browser/private identity inputs. */
import {createHash, randomBytes} from 'node:crypto';
import {mkdir, writeFile, mkdtemp, lstat, readdir, open, rename, rm} from 'node:fs/promises';
import {constants} from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {resolveLatestRelease, validateDescriptor, verifyTemplateBytes, MAX_DESCRIPTOR_SIZE} from '../core/releases.mjs';
import {parseStrictJson} from '../core/strict-json.mjs';
import {readArchive} from '../core/archive.mjs';
import {transformAabEntries} from '../core/transform.mjs';

const DESCRIPTOR='openautolink-personalization.json';
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');

class MirrorError extends Error {}
const fail=message=>{throw new MirrorError(message);};
function cancel(body) {try {body?.cancel()?.catch(()=>{});} catch {}}
function wait(promise,signal,discard) {
 return new Promise((resolve,reject)=>{
  const abort=()=>reject(signal.reason);
  signal.addEventListener('abort',abort,{once:true});
  if(signal.aborted)abort();
  Promise.resolve(promise).then(value=>{
   signal.removeEventListener('abort',abort);
   if(signal.aborted){discard?.(value);reject(signal.reason);}else resolve(value);
  },error=>{signal.removeEventListener('abort',abort);reject(error);});
 });
}

async function safeDirectory(dir,create=false) {
 const parent=path.dirname(dir);
 if(parent!==dir)await safeDirectory(parent,create);
 let info;
 try {info=await lstat(dir);} catch(error){
  if(error.code!=='ENOENT')throw error;
  if(!create)return;
  try {await mkdir(dir,{mode:0o755});} catch(error){if(error.code!=='EEXIST')throw error;}
  info=await lstat(dir);
 }
 if(!info.isDirectory()||info.isSymbolicLink())fail('Output directory must not contain symlinks or non-directories');
}
async function verifyExisting(target,files) {
 const info=await lstat(target);
 if(!info.isDirectory()||info.isSymbolicLink())fail('Existing output mismatch (directory or symlink)');
 if(JSON.stringify((await readdir(target)).sort())!==JSON.stringify(Object.keys(files).sort()))fail('Existing output mismatch (file set)');
 for(const [name,bytes] of Object.entries(files)) {
  const full=path.join(target,name),info=await lstat(full);
  if(!info.isFile()||info.isSymbolicLink()||info.size!==bytes.length)fail('Existing output mismatch (file type or size)');
  const handle=await open(full,constants.O_RDONLY|constants.O_NOFOLLOW);
  try {
   // Check again after opening and never allocate from untrusted file size.
   const current=await handle.stat();
   if(!current.isFile()||current.size!==bytes.length)fail('Existing output mismatch (file size)');
   const buffer=Buffer.alloc(bytes.length);let offset=0;
   while(offset<buffer.length){const {bytesRead}=await handle.read(buffer,offset,buffer.length-offset,offset);if(!bytesRead)break;offset+=bytesRead;}
   if(offset!==bytes.length||!buffer.equals(Buffer.from(bytes)))fail('Existing output mismatch (bytes)');
  } finally {await handle.close();}
 }
}
async function install(outputDir,tag,files,signal) {
 await safeDirectory(path.dirname(outputDir),true);
 const lock=path.join(path.dirname(outputDir),'.oal-mirror-lock-'+sha(Buffer.from(outputDir)).slice(0,24));
 try {await mkdir(lock,{mode:0o700});} catch {fail('Mirror output locked; another preparation may be running');}
 let staging;
 try {
  signal.throwIfAborted();
  await safeDirectory(path.join(outputDir,'templates'),true);
  const target=path.join(outputDir,'templates',tag);
  let exists=true;
  try {await lstat(target);} catch(error){if(error.code!=='ENOENT')throw error;exists=false;}
  if(exists){await verifyExisting(target,files);return 'already-identical';}
  // Same-filesystem sibling, never inside the served root. Commit both files
  // with one directory rename. A lock serializes cooperating CLI invocations.
  staging=await mkdtemp(path.join(path.dirname(outputDir),'.oal-mirror-stage-'));
  const staged=path.join(staging,tag);await mkdir(staged,{mode:0o755});
  for(const [name,bytes] of Object.entries(files))await writeFile(path.join(staged,name),bytes,{flag:'wx',mode:0o644});
  signal.throwIfAborted();
  await rename(staged,target);
  await verifyExisting(target,files);
  return 'prepared';
 } finally {
  if(staging)await rm(staging,{recursive:true,force:true});
  await rm(lock,{recursive:true,force:true});
 }
}

export async function prepareMirror({outputDir,checkOnly=false,fetchImpl=globalThis.fetch,timeoutMs=120000}={}) {
 if(typeof checkOnly!=='boolean'||(checkOnly&&outputDir!==undefined))fail('Choose check-only OR an explicit output directory');
 if(!checkOnly&&(typeof outputDir!=='string'||!outputDir.trim()||!path.isAbsolute(outputDir)||path.resolve(outputDir)===path.parse(outputDir).root))fail('An explicit absolute output directory is required');
 if(outputDir){outputDir=path.resolve(outputDir);await safeDirectory(outputDir);}
 if(!Number.isSafeInteger(timeoutMs)||timeoutMs<1||timeoutMs>120000)fail('Invalid timeout (1..120000 ms)');
 const controller=new AbortController();
 const timer=setTimeout(()=>controller.abort(new MirrorError('Public mirror preparation timed out')),timeoutMs);
 try {return await prepare({outputDir,checkOnly,fetchImpl,signal:controller.signal});}
 catch(error){controller.signal.throwIfAborted();throw error;}
 finally {clearTimeout(timer);}
}

async function prepare({outputDir,checkOnly,fetchImpl,signal}) {
 const read=async (url,limit,exact)=>{
  const initial=url;
  let response,reader,finished=false;
  try {
   for(let redirects=0;;redirects++) {
    signal.throwIfAborted();
    response=await wait(fetchImpl(url,{method:'GET',credentials:'omit',redirect:'manual',referrerPolicy:'no-referrer',cache:'no-store',signal,headers:{Accept:initial.startsWith('https://api.github.com/')?'application/vnd.github+json':'application/octet-stream','Accept-Encoding':'identity'}}),signal,r=>cancel(r?.body));
    if(response.redirected||(response.url&&response.url!==url))fail('Unexpected public redirect');
    if(![301,302,303,307,308].includes(response.status))break;
    cancel(response.body);
    if(initial.startsWith('https://api.github.com/')||redirects>=5)fail('Public redirect not allowed or limit exceeded');
    let next;
    try {
     const location=response.headers.get('Location');
     if(!location||location.length>16384||/[\x00-\x20\x7f\\]/.test(location))fail('Unsafe public redirect');
     next=new URL(location,url);
    } catch {fail('Unsafe public redirect');}
    if(next.protocol!=='https:'||next.username||next.password||next.port||next.hash
       || !['github.com','release-assets.githubusercontent.com','objects.githubusercontent.com'].includes(next.hostname)
       || (next.hostname==='github.com'&&next.href!==initial))fail('Unsafe public redirect');
    url=next.href;
   }
   if(response.status!==200)fail('Public download unavailable');
   const length=response.headers.get('Content-Length');
   if(length!==null&&(!/^[0-9]{1,16}$/.test(length)||!Number.isSafeInteger(Number(length))||Number(length)>limit||(exact!==undefined&&Number(length)!==exact)))fail('Public download size or Content-Length mismatch');
   if(response.headers.get('Content-Encoding')&&!/^identity$/i.test(response.headers.get('Content-Encoding')))fail('Unexpected public content encoding');
   const bytes=new Uint8Array(limit);let size=0;
   reader=response.body.getReader();
   for(;;){
    const {done,value}=await wait(reader.read(),signal);
    if(done){finished=true;break;}
    if(!(value instanceof Uint8Array)||value.length>limit-size)fail('Public download size limit exceeded');
    bytes.set(value,size);size+=value.length;
   }
   if((exact!==undefined&&size!==exact)||(length!==null&&size!==Number(length)))fail('Public download size mismatch (truncated)');
   return bytes.subarray(0,size);
  } catch(error) {
   signal.throwIfAborted();
   if(error instanceof MirrorError)throw error;
   fail('Public download failed; check connectivity or GitHub availability');
  } finally {
   if(!finished)cancel(reader??response?.body);
   reader?.releaseLock();
  }
 };
 const release=await resolveLatestRelease(async url=>parseStrictJson(await read(url,1024**2),{maxBytes:1024**2}));
 if(!release.templateAvailable) {
  const missing=[DESCRIPTOR,`openautolink-template-${release.sourceRef}.aab`].filter(name=>!Object.hasOwn(release.assets,name));
  fail(`Latest public template unavailable: ${release.sourceRef} (${release.sourceCommit}); missing ${missing.join(', ')}; no older/local fallback`);
 }
 const record=release.assets[DESCRIPTOR];
 if(record.size>MAX_DESCRIPTOR_SIZE||!record.digest)throw Error('Descriptor size or SHA-256 unavailable');
 const raw=await read(record.browser_download_url,record.size,record.size);
 if('sha256:'+sha(raw)!==record.digest)throw Error('Descriptor checksum mismatch');
 let parsed;
 try {parsed=parseStrictJson(raw);} catch {fail('Malformed or duplicate descriptor JSON');}
 const descriptor=validateDescriptor(parsed,release);
 const bytes=await verifyTemplateBytes(await read(descriptor.url,descriptor.templateAsset.size,descriptor.templateAsset.size),descriptor);
 transformAabEntries(readArchive(bytes),{applicationId:'com.example.mirrorcheck.p'+randomBytes(16).toString('hex'),versionCode:1,versionName:release.versionName});
 const status=checkOnly?'checked':await install(outputDir,release.sourceRef,{[DESCRIPTOR]:raw,[descriptor.templateAsset.name]:bytes},signal);
 return {status,sourceRef:release.sourceRef,sourceCommit:release.sourceCommit,descriptorSha256:sha(raw),templateSha256:sha(bytes),transport:fetchImpl===globalThis.fetch?'public-github':'injected-fixture-not-public-proof',validation:'unsigned-credential-free-structure-and-transform-dry-run'};
}

if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href) {
 try {
  const args=process.argv.slice(2);
  if(args.length===1&&args[0]==='--help') {
   console.log('Usage: node scripts/mirror-template.mjs --check\n       node scripts/mirror-template.mjs --output-dir /absolute/static-root\nRelease-only public GitHub preparation; no deployment, uploads, private inputs or older/local fallback.\nA sanitized JSON provenance report is written to stdout; retain it OUTSIDE the static web root.');
  } else {
   let options;
   if(args.length===1&&args[0]==='--check')options={checkOnly:true};
   else if(args.length===2&&args[0]==='--output-dir')options={outputDir:args[1]};
   else fail('Invalid arguments; use --help (only --check OR --output-dir is supported)');
   console.log(JSON.stringify(await prepareMirror(options),null,2));
  }
 } catch(error) {
  // Do not print Error stacks/causes or fetch Response/URL objects.
  const message=error?.code?'Local mirror filesystem operation failed':error.message;
  console.error(JSON.stringify({status:'error',message}));process.exitCode=1;
 }
}
