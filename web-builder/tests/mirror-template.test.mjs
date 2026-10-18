import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, readFile, readdir, rm, writeFile, mkdir, symlink} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {writeArchive} from '../core/archive.mjs';
import {entries} from './transform-fixtures.mjs';
import {spawnSync} from 'node:child_process';
import {prepareMirror} from '../scripts/mirror-template.mjs';

const TAG='v0.1.528', COMMIT='a'.repeat(40);
const API='https://api.github.com/repos/mossyhub/openautolink';
const WEB='https://github.com/mossyhub/openautolink';
const NAME='openautolink-personalization.json', AAB=`openautolink-template-${TAG}.aab`;
const url=name=>`${WEB}/releases/download/${TAG}/${name}`;
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const json=value=>Buffer.from(JSON.stringify(value));
function fixture({bundle=writeArchive(entries()), descriptor, raw, missing=false}={}) {
 const parsed=descriptor??{schemaVersion:1,formatVersion:1,releaseTag:TAG,sourceCommit:COMMIT,templateApplicationId:'com.openautolink.template',transformContract:'oal-identity-v1',templateAsset:{name:AAB,size:bundle.length,sha256:sha(bundle)}};
 raw??=json(parsed);
 const asset=(name,bytes)=>({name,size:bytes.length,digest:'sha256:'+sha(bytes),browser_download_url:url(name)});
 const metadata={tag_name:TAG,draft:false,prerelease:false,html_url:`${WEB}/releases/tag/${TAG}`,assets:missing?[]:[asset(NAME,raw),asset(AAB,bundle)]};
 const routes=new Map([[`${API}/releases/latest`,json(metadata)],[`${API}/git/ref/tags/${TAG}`,json({ref:`refs/tags/${TAG}`,object:{type:'commit',sha:COMMIT}})],[url(NAME),raw],[url(AAB),bundle]]);
 const calls=[];
 const fetchImpl=async (target,options)=>{
  calls.push({target,options});
  assert.ok(routes.has(target),'unexpected fetch target');
  const value=routes.get(target);
  return typeof value==='function'?value(options):new Response(value);
 };
 return {bundle,raw,parsed,metadata,routes,calls,fetchImpl};
}
async function workspace(t) {
 const dir=await mkdtemp(path.join(os.tmpdir(),'oal-mirror-test-'));
 t.after(()=>rm(dir,{recursive:true,force:true}));
 return path.join(dir,'public');
}

const invalidCases=[
 ['duplicate descriptor keys',()=>fixture({raw:Buffer.from('{"schemaVersion":1,"schemaVersion":1}')}),/JSON/],
 ['escaped duplicate nested keys',()=>fixture({raw:Buffer.from('{"templateAsset":{"name":"a","n'+String.fromCharCode(92)+'u0061me":"b"}}')}),/JSON/],
 ['invalid UTF-8 descriptor',()=>fixture({raw:Buffer.from([0xff,0xfe])}),/JSON/],
 ['oversized raw descriptor',()=>fixture({raw:Buffer.alloc(65537,32)}),/size/],
 ['wrong descriptor source commit',()=>{const f=fixture();return fixture({descriptor:{...f.parsed,sourceCommit:'b'.repeat(40)}});},/descriptor/],
 ['wrong descriptor release tag',()=>{const f=fixture();return fixture({descriptor:{...f.parsed,releaseTag:'v0.1.527'}});},/descriptor/],
 ['unexpected descriptor credential field',()=>{const f=fixture();return fixture({descriptor:{...f.parsed,privateKey:'synthetic-not-a-key'}});},/descriptor/],
 ['wrong template SHA-256',()=>{const f=fixture();return fixture({descriptor:{...f.parsed,templateAsset:{...f.parsed.templateAsset,sha256:'0'.repeat(64)}}});},/checksum/],
 ...['base/assets/offline-mfi/auth.bin','base/assets/upload.pem','base/root/key.p12','base/assets/upload.keystore'].map(name=>['credential archive member '+name,()=>fixture({bundle:writeArchive([...entries(),{name,data:Buffer.from('synthetic-not-a-key')}])}),/CREDENTIAL/]),
 ['signed template',()=>fixture({bundle:writeArchive([...entries(),{name:'META-INF/UPLOAD.RSA',data:Buffer.from('synthetic signature')}])}),/SIGNED/],
 ['malformed ZIP',()=>fixture({bundle:Buffer.from('not a zip')}),/ZIP/],
 ['unsupported transform structure',()=>fixture({bundle:writeArchive(entries({omit:['provider']}))}),/OAL_PERSONALIZE/],
];
for(const [label,make,expected] of invalidCases)test('rejects '+label+' before filesystem writes',async t=>{
 const outputDir=await workspace(t),f=make();
 await assert.rejects(prepareMirror({outputDir,fetchImpl:f.fetchImpl}),expected);
 assert.deepEqual(await readdir(path.dirname(outputDir)),[]);
});

for(const [label,mutate,expected] of [
 ['missing descriptor digest',f=>{f.metadata.assets[0].digest=null;},/SHA-256/],
 ['descriptor digest mismatch',f=>{f.metadata.assets[0].digest='sha256:'+'0'.repeat(64);},/checksum/],
 ['initial malicious asset host',f=>{f.metadata.assets[0].browser_download_url='https://evil.test/asset';},/untrusted/],
 ['initial asset userinfo',f=>{f.metadata.assets[0].browser_download_url=url(NAME).replace('https://','https://user:secret@');},/untrusted/],
 ['initial asset query',f=>{f.metadata.assets[0].browser_download_url+='?secret=DO_NOT_LOG';},/untrusted/],
 ['nonstable latest',f=>{f.metadata.prerelease=true;},/stable/],
 ['missing latest AAB only',f=>{f.metadata.assets.pop();},/missing openautolink-template/],
 ['oversized template declaration',f=>{f.metadata.assets[1].size=512*1024**2+1;},/size/],
])test('rejects '+label,async t=>{
 const f=fixture();mutate(f);f.routes.set(`${API}/releases/latest`,json(f.metadata));
 await assert.rejects(prepareMirror({checkOnly:true,fetchImpl:f.fetchImpl}),expected);
 assert.ok(f.calls.every(({target})=>!target.includes('evil.test')&&!target.includes('secret')));
});

for(const [name,stage] of [[NAME,'descriptor'],[AAB,'template']]) {
 for(const [label,body,headers] of [
  ['truncation',bytes=>bytes.subarray(0,-1),{}],
  ['overflow',bytes=>Buffer.concat([bytes,Buffer.from([0])]),{}],
  ['false Content-Length',bytes=>bytes,{'Content-Length':'1'}],
  ['invalid Content-Length',bytes=>bytes,{'Content-Length':'1e2'}],
  ['encoded body',bytes=>bytes,{'Content-Encoding':'gzip'}],
 ])test(stage+' rejects '+label,async()=>{
  const f=fixture(),bytes=Buffer.from(name===NAME?f.raw:f.bundle);
  f.routes.set(url(name),()=>new Response(body(bytes),{headers}));
  await assert.rejects(prepareMirror({checkOnly:true,fetchImpl:f.fetchImpl}),/size|encoding/);
 });
}

test('AAB hash verified even without optional GitHub AAB digest',async()=>{
 const f=fixture();f.metadata.assets[1].digest=null;f.routes.set(`${API}/releases/latest`,json(f.metadata));
 const corrupted=Buffer.from(f.bundle);corrupted[0]^=1;f.routes.set(url(AAB),corrupted);
 await assert.rejects(prepareMirror({checkOnly:true,fetchImpl:f.fetchImpl}),/checksum/);
});

test('refuses automatic redirects, loops, API redirects and oversized metadata',async()=>{
 for(const scenario of ['auto','loop','api-redirect','metadata-overflow','metadata-truncated']){
  const f=fixture();
  if(scenario==='auto')f.routes.set(url(NAME),()=>({status:200,redirected:true,body:new ReadableStream(),headers:new Headers()}));
  if(scenario==='loop')f.routes.set(url(NAME),()=>new Response(null,{status:302,headers:{Location:url(NAME)}}));
  if(scenario==='api-redirect')f.routes.set(`${API}/releases/latest`,()=>new Response(null,{status:302,headers:{Location:'https://api.github.com/elsewhere'}}));
  if(scenario==='metadata-overflow')f.routes.set(`${API}/releases/latest`,Buffer.alloc(1024**2+1));
  if(scenario==='metadata-truncated')f.routes.set(`${API}/releases/latest`,()=>new Response(json(f.metadata),{headers:{'Content-Length':String(json(f.metadata).length+1)}}));
  await assert.rejects(prepareMirror({checkOnly:true,fetchImpl:f.fetchImpl}),/redirect|metadata/);
  assert.ok(f.calls.length<=8);
 }
});

test('deadline also cancels a hanging response body without exposing its query',async()=>{
 const f=fixture();let cancelled=false;
 f.routes.set(url(NAME),()=>new Response(new ReadableStream({pull(){return new Promise(()=>{});},cancel(){cancelled=true;}})));
 await assert.rejects(prepareMirror({checkOnly:true,fetchImpl:f.fetchImpl,timeoutMs:30}),/timed out/);
 assert.equal(cancelled,true);
});

test('optional local sanitized template integration is NOT public-latest proof',{
 skip:!process.env.OAL_MIRROR_LOCAL_TEMPLATE||!process.env.OAL_MIRROR_LOCAL_DESCRIPTOR,
 timeout:120000,
},async t=>{
 const bundle=await readFile(process.env.OAL_MIRROR_LOCAL_TEMPLATE);
 const raw=await readFile(process.env.OAL_MIRROR_LOCAL_DESCRIPTOR);
 const descriptor=JSON.parse(raw.toString('utf8'));
 assert.equal(descriptor.releaseTag,TAG,'this retained local fixture is v0.1.528, never current public latest');
 const f=fixture({bundle,raw});
 f.routes.set(`${API}/git/ref/tags/${TAG}`,json({ref:`refs/tags/${TAG}`,object:{type:'commit',sha:descriptor.sourceCommit}}));
 const outputDir=await workspace(t);
 const report=await prepareMirror({outputDir,fetchImpl:f.fetchImpl});
 assert.equal(report.transport,'injected-fixture-not-public-proof');
 assert.equal(report.sourceCommit,descriptor.sourceCommit);
 assert.equal(report.templateSha256,descriptor.templateAsset.sha256);
 assert.deepEqual(await readFile(path.join(outputDir,'templates',TAG,AAB)),bundle);
 assert.deepEqual(await readFile(path.join(outputDir,'templates',TAG,NAME)),raw);
 t.diagnostic(JSON.stringify({qualification:'local-sanitized-template-only-not-public-latest',...report}));
});

test('existing output rejects equal-length changed bytes, extra files and symlink members',async t=>{
 const outputDir=await workspace(t),f=fixture();await prepareMirror({outputDir,fetchImpl:f.fetchImpl});
 const target=path.join(outputDir,'templates',TAG),file=path.join(target,NAME);
 const altered=Buffer.from(f.raw);altered[0]^=1;await writeFile(file,altered);
 await assert.rejects(prepareMirror({outputDir,fetchImpl:f.fetchImpl}),/mismatch.*bytes/);
 assert.deepEqual(await readFile(file),altered);
 await writeFile(file,f.raw);await writeFile(path.join(target,'unexpected.txt'),'sentinel');
 await assert.rejects(prepareMirror({outputDir,fetchImpl:f.fetchImpl}),/mismatch.*file set/);
 await rm(path.join(target,'unexpected.txt'));await rm(file);
 const outside=path.join(path.dirname(outputDir),'original.json');await writeFile(outside,f.raw);await symlink(outside,file);
 await assert.rejects(prepareMirror({outputDir,fetchImpl:f.fetchImpl}),/mismatch/);
 assert.deepEqual(await readFile(outside),f.raw);
});

test('missing live-latest assets names exact missing files and never falls back',async t=>{
 const f=fixture({missing:true}),outputDir=await workspace(t);
 await assert.rejects(prepareMirror({outputDir,fetchImpl:f.fetchImpl}),error=>error.message.includes(TAG)&&error.message.includes(COMMIT)&&error.message.includes(NAME)&&error.message.includes(AAB)&&error.message.includes('no older/local fallback'));
 assert.equal(f.calls.length,2);assert.deepEqual(await readdir(path.dirname(outputDir)),[]);
});

test('CLI documents modes, rejects missing/unknown/private inputs without echoing them',()=>{
 const run=args=>spawnSync(process.execPath,['scripts/mirror-template.mjs',...args],{encoding:'utf8'});
 const help=run(['--help']);assert.equal(help.status,0);assert.match(help.stdout,/--check/);assert.match(help.stdout,/--output-dir/);
 for(const args of [[],['--url','https://evil.test?token=DO_NOT_LOG'],['--template-file','DO_NOT_LOG'],['--check','--output-dir','/public'],['--output-dir','relative'],['--check','--check']]){
  const result=run(args);assert.equal(result.status,1);assert.ok(!result.stderr.includes('DO_NOT_LOG'));assert.match(result.stderr,/error/);
 }
});

test('malformed descriptor errors do not echo remote data',async t=>{
 const f=fixture({raw:Buffer.from('{"sourceCommit": DO_NOT_LOG}')});
 await assert.rejects(prepareMirror({checkOnly:true,fetchImpl:f.fetchImpl}),error=>/JSON/.test(error.message)&&!error.message.includes('DO_NOT_LOG'));
});

test('existing identical output is idempotent; mismatched bytes are never overwritten',async t=>{
 const outputDir=await workspace(t),f=fixture();
 assert.equal((await prepareMirror({outputDir,fetchImpl:f.fetchImpl})).status,'prepared');
 assert.equal((await prepareMirror({outputDir,fetchImpl:f.fetchImpl})).status,'already-identical');
 const target=path.join(outputDir,'templates',TAG,NAME);
 await writeFile(target,'do not overwrite');
 await assert.rejects(prepareMirror({outputDir,fetchImpl:f.fetchImpl}),/existing.*mismatch/i);
 assert.equal(await readFile(target,'utf8'),'do not overwrite');
 assert.deepEqual(await readdir(path.dirname(outputDir)),['public']);
});

test('check-only validates but creates no files; writing requires explicit directory',async t=>{
 const outputDir=await workspace(t),f=fixture();
 assert.equal((await prepareMirror({checkOnly:true,fetchImpl:f.fetchImpl})).status,'checked');
 assert.deepEqual(await readdir(path.dirname(outputDir)),[]);
 await assert.rejects(prepareMirror({fetchImpl:f.fetchImpl}),/output directory/i);
});

test('refuses symlink output ancestors and partial existing releases',async t=>{
 const outputDir=await workspace(t),f=fixture();
 const elsewhere=path.join(path.dirname(outputDir),'elsewhere');await mkdir(elsewhere);
 await symlink(elsewhere,outputDir);
 await assert.rejects(prepareMirror({outputDir,fetchImpl:f.fetchImpl}),/symlink/i);
 assert.deepEqual(await readdir(elsewhere),[]);
 await rm(outputDir);await mkdir(path.join(outputDir,'templates',TAG),{recursive:true});
 await assert.rejects(prepareMirror({outputDir,fetchImpl:f.fetchImpl}),/existing.*mismatch/i);
 assert.deepEqual(await readdir(path.join(outputDir,'templates',TAG)),[]);
});

test('bounds streaming bytes, cancels overflow, never calls arrayBuffer',async t=>{
 const f=fixture(),outputDir=await workspace(t); let cancelled=false;
 f.routes.set(url(NAME),()=>({status:200,headers:new Headers(),body:new ReadableStream({pull(c){c.enqueue(new Uint8Array(f.raw.length+1));},cancel(){cancelled=true;}}),arrayBuffer(){assert.fail('unbounded reader used');}}));
 await assert.rejects(prepareMirror({outputDir,fetchImpl:f.fetchImpl}),/size/i);
 assert.equal(cancelled,true);
});

test('deadline bounds hanging fetch and sanitizes transport exceptions',async t=>{
 const outputDir=await workspace(t),f=fixture();
 f.routes.set(url(NAME),()=>new Promise(()=>{}));
 await assert.rejects(prepareMirror({outputDir,fetchImpl:f.fetchImpl,timeoutMs:30}),/timed out/i);
 f.routes.set(url(NAME),()=>{throw Error('https://release-assets.githubusercontent.com/file?token=DO_NOT_LOG');});
 await assert.rejects(prepareMirror({outputDir,fetchImpl:f.fetchImpl}),error=>!error.message.includes('DO_NOT_LOG')&&/download failed/i.test(error.message));
});

test('follows bounded allowlisted HTTPS redirects without credentials or token reports',async t=>{
 const f=fixture(), outputDir=await workspace(t);
 const signed='https://release-assets.githubusercontent.com/asset?secret=DO_NOT_LOG';
 f.routes.set(url(NAME),()=>new Response(null,{status:302,headers:{Location:signed}}));
 f.routes.set(signed,()=>new Response(null,{status:307,headers:{Location:'https://objects.githubusercontent.com/final?token=DO_NOT_LOG'}}));
 f.routes.set('https://objects.githubusercontent.com/final?token=DO_NOT_LOG',f.raw);
 const report=await prepareMirror({outputDir,fetchImpl:f.fetchImpl});
 assert.equal(f.calls.length,6);
 assert.ok(!JSON.stringify(report).includes('DO_NOT_LOG'));
});

for(const location of ['http://github.com/file','https://github.com.evil.test/file','https://evil.test/file','https://user:secret@release-assets.githubusercontent.com/file','https://release-assets.githubusercontent.com:444/file','https://github.com/other/repo/releases/download/v1.0.0/file','https://api.github.com/repos/mossyhub/openautolink/releases/latest','https://release-assets.githubusercontent.com/file#secret']) {
 test('rejects unsafe redirect '+location.split('?')[0],async t=>{
  const f=fixture(),outputDir=await workspace(t);
  f.routes.set(url(NAME),()=>new Response(null,{status:302,headers:{Location:location}}));
  await assert.rejects(prepareMirror({outputDir,fetchImpl:f.fetchImpl}),/redirect/i);
  assert.equal(f.calls.length,3);
 });
}

test('prepares only exact public descriptor and template bytes, dry-run validates unsigned structure',async t=>{
 const outputDir=await workspace(t), f=fixture();
 const report=await prepareMirror({outputDir,fetchImpl:f.fetchImpl});
 const target=path.join(outputDir,'templates',TAG);
 assert.deepEqual((await readdir(target)).sort(),[NAME,AAB].sort());
 assert.deepEqual(await readFile(path.join(target,NAME)),f.raw);
 assert.deepEqual(await readFile(path.join(target,AAB)),Buffer.from(f.bundle));
 assert.equal(report.sourceRef,TAG); assert.equal(report.sourceCommit,COMMIT);
 assert.equal(report.templateSha256,sha(f.bundle));
 assert.equal(report.transport,'injected-fixture-not-public-proof');
 assert.equal(report.validation,'unsigned-credential-free-structure-and-transform-dry-run');
 assert.equal(f.calls.length,4);
 for(const {target,options} of f.calls){
  assert.equal(options.headers.Accept,target.startsWith(API)?'application/vnd.github+json':'application/octet-stream');
  assert.equal(options.credentials,'omit'); assert.equal(options.redirect,'manual');
  assert.equal(options.method,'GET'); assert.equal(options.referrerPolicy,'no-referrer');
  assert.equal(options.headers['Accept-Encoding'],'identity');
  assert.equal(options.headers.Authorization,undefined); assert.equal(options.headers.Cookie,undefined);
 }
});
