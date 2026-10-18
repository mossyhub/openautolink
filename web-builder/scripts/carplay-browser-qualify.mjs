// Local real-browser qualification; ONLY generated synthetic CarPlay credentials.
import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdtemp,rm,mkdir} from 'node:fs/promises';
import {join,dirname} from 'node:path';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {readArchive} from '../core/archive.mjs';
const hash=b=>createHash('sha256').update(b).digest('hex');
const templatePath=process.env.OAL_WEB_TEMPLATE,tools=process.env.OAL_WEB_TOOLS;
assert.ok(templatePath&&tools&&process.env.TMPDIR,'Set template/tools/TMPDIR');
const temp=await mkdtemp(join(process.env.TMPDIR,'oal-browser-synthetic-'));
let browser;
const run=(exe,args)=>{
 const r=spawnSync(exe,args,{encoding:'utf8',timeout:120000,maxBuffer:4*1024**2});
 assert.equal(r.status,0,'Independent qualification command failed');return r.stdout+r.stderr;
};
try{
 run(process.env.CARPLAY_FIXTURE_PYTHON||'/hermes_work/oal-builder-verify-venv/bin/python',['tests/carplay-fixtures/generate.py',temp]);
 const template=await readFile(templatePath),descriptor=JSON.parse(await readFile(join(dirname(templatePath),'openautolink-personalization.json'),'utf8'));
 assert.equal(hash(template),descriptor.templateAsset.sha256);
 const paths=['p256.pk8','p256.der','single.p7b'];const fixtures=Object.fromEntries(await Promise.all(paths.map(async n=>[n,await readFile(join(temp,n))])));
 browser=await chromium.launch({executablePath:process.env.OAL_BROWSER,headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
 const context=await browser.newContext({serviceWorkers:'block'}),unexpected=[];
 await context.route('**/*',async route=>{
  const r=route.request(),url=new URL(r.url());
  if(url.origin!=='http://127.0.0.1:5187'||r.method()!=='GET'){unexpected.push({method:r.method(),external:true});return route.abort();}
  if(url.pathname==='/')return route.fulfill({contentType:'text/html',body:'<!doctype html><title>Synthetic CarPlay qualification</title>'});
  if(url.pathname==='/fixture-template')return route.fulfill({contentType:'application/octet-stream',body:template});
  return route.continue();
 });
 const page=await context.newPage();
 await page.exposeFunction('saveSyntheticArtifact',async(name,data)=>{
  assert.ok(['der.aab','pkcs7.aab','upload.pem'].includes(name));
  await writeFile(join(temp,name),Buffer.from(data,'base64'),{flag:'wx'});
 });
 await page.goto('http://127.0.0.1:5187/');
 const result=await page.evaluate(async({descriptor,fixtureBytes})=>{
  const [{runBuildInWorker},{createIdentity,restoreIdentity},{readArchive}]=await Promise.all([import('/core/worker-client.mjs'),import('/core/identity.mjs'),import('/core/archive.mjs')]);
  const check=x=>{if(!x)throw Error('Synthetic qualification assertion failed');};
  const bytes=Object.fromEntries(Object.entries(fixtureBytes).map(([k,v])=>[k,Uint8Array.from(v)]));
  const templateBytes=new Uint8Array(await(await fetch('/fixture-template')).arrayBuffer());
  let identity=await createIdentity('com.example.carplaybrowser');
  const password=Array.from(crypto.getRandomValues(new Uint8Array(24)),b=>b.toString(16).padStart(2,'0')).join('');
  const save=async(name,data)=>{let s='';for(let i=0;i<data.length;i+=32768)s+=String.fromCharCode(...data.subarray(i,i+32768));await window.saveSyntheticArtifact(name,btoa(s));};
  await save('upload.pem',new TextEncoder().encode(identity.certificatePem));
  const builds=[];
  for(const [format,certName,versionCode] of [['der','p256.der',1],['pkcs7','single.p7b',2]]){
   const original=structuredClone(identity);
   const output=await runBuildInWorker({templateBytes,descriptor,identity,versionCode,backupPassword:password,carplay:{keyBytes:bytes['p256.pk8'],certificateBytes:bytes[certName]}});
   const restored=await restoreIdentity(output.backupBytes,password);
   check(JSON.stringify(restored)===JSON.stringify({...original,versionCodeHighWaterMark:versionCode}));
   const entries=readArchive(output.aabBytes),get=n=>entries.find(e=>e.name===n)?.data;
   for(const [path,expected] of [['base/assets/offline-mfi/identity.pk8',bytes['p256.pk8']],['base/assets/offline-mfi/certificate.p7b',bytes[certName]]]){
    const data=get(path);check(data?.length===expected.length&&data.every((b,i)=>b===expected[i]));
    check(new TextDecoder().decode(get('META-INF/MANIFEST.MF')).includes('Name: '+path+'\r\n'));
   }
   check(entries.filter(e=>e.name.includes('/offline-mfi/')).length===2);
   await save(format+'.aab',output.aabBytes);identity=restored;
   builds.push({format,versionCode,backupExcludesCarplay:true,exactSelectedAssets:true,signingManifestCoversAssets:true});
  }
  return {browser:navigator.userAgent,builds,scope:'Synthetic CarPlay pair in local prepared template; no Apple trust or iPhone acceptance proof'};
 },{descriptor,fixtureBytes:Object.fromEntries(Object.entries(fixtures).map(([k,v])=>[k,[...v]]))});
 assert.deepEqual(unexpected,[]);
 const java=join(tools,'jdk-17/bin/java'),keytool=join(tools,'jdk-17/bin/keytool'),jarsigner=join(tools,'jdk-17/bin/jarsigner'),trust=join(temp,'trust.p12');
 run(keytool,['-importcert','-noprompt','-alias','upload','-file',join(temp,'upload.pem'),'-keystore',trust,'-storetype','PKCS12','-storepass','synthetic-public-trust']);
 for(const item of result.builds){
  const path=join(temp,item.format+'.aab');
  run(java,['-jar',join(tools,'bundletool-1.18.3.jar'),'validate','--bundle='+path]);
  assert.match(run(jarsigner,['-verify','-strict','-keystore',trust,'-storepass','synthetic-public-trust',path]),/jar verified/);
  item.bundletoolValidated=true;item.jarsignerStrictVerified=true;
  const entries=readArchive(new Uint8Array(await readFile(path)));
  for(const entry of readArchive(new Uint8Array(template)))if(!['base/resources.pb','base/manifest/AndroidManifest.xml'].includes(entry.name))assert.equal(hash(entries.find(x=>x.name===entry.name).data),hash(entry.data));
  item.compiledPayloadsUnchanged=true;
 }
 result.unexpectedNetworkRequests=unexpected;
 await mkdir('qualification-output',{recursive:true});
 await writeFile('qualification-output/carplay-browser.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
}finally{if(browser)await browser.close();await rm(temp,{recursive:true,force:true});}
