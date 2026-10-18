// Local qualification only: real browser computation, no server-side signing.
import { chromium } from '@playwright/test';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
const root=resolve('qualification-output/browser');
await mkdir(root,{recursive:true});
const templatePath=process.env.OAL_WEB_TEMPLATE;
const tools=process.env.OAL_WEB_TOOLS;
assert.ok(templatePath&&tools,'Set OAL_WEB_TEMPLATE and OAL_WEB_TOOLS');
const template=await readFile(templatePath);
const templateHash=createHash('sha256').update(template).digest('hex');
const executablePath=process.env.OAL_BROWSER;
const browser=await chromium.launch({executablePath,headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
try{
 const context=await browser.newContext();const page=await context.newPage();
 const requests=[];const forbidden=[];
 await context.route('**/*',async route=>{
  const r=route.request(),url=new URL(r.url());
  requests.push({method:r.method(),path:url.pathname,origin:url.origin});
  if(url.origin!=='http://127.0.0.1:5187'||r.method()!=='GET'){forbidden.push(r.url());return route.abort();}
  if(url.pathname==='/')return route.fulfill({contentType:'text/html',body:'<!doctype html><title>OAL local browser qualification</title><main>Local browser AAB qualification</main>'});
  if(url.pathname==='/qualification/template.aab')return route.fulfill({contentType:'application/octet-stream',body:template});
  return route.continue();
 });
 await page.exposeFunction('saveQualificationArtifact',async(name,base64)=>{
  assert.ok(['first.aab','update.aab','upload.pem'].includes(name));
  await writeFile(join(root,name),Buffer.from(base64,'base64'));
 });
 await page.goto('http://127.0.0.1:5187/');
 const result=await page.evaluate(async({templateHash,templateSize})=>{
  const start=performance.now();
  const [{readArchive,writeArchive},{transformAabEntries},{createIdentity,backupIdentity,restoreIdentity},{signEntries}]=await Promise.all([
   import('/core/archive.mjs'),import('/core/transform.mjs'),import('/core/identity.mjs'),import('/core/sign.mjs')]);
  if(!isSecureContext||!crypto.subtle)throw Error('Missing secure-context WebCrypto');
  const hex=bytes=>Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
  const hash=async bytes=>hex(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)));
  const bytes=new Uint8Array(await(await fetch('/qualification/template.aab')).arrayBuffer());
  if(bytes.length!==templateSize||await hash(bytes)!==templateHash)throw Error('Template bytes changed');
  const entries=readArchive(bytes);
  let identity=await createIdentity('com.example.browserqualification');
  const cert=identity.certificatePem;
  const password=hex(crypto.getRandomValues(new Uint8Array(24)));
  const save=async(name,bytes)=>{
   let binary='';for(let i=0;i<bytes.length;i+=32768)binary+=String.fromCharCode(...bytes.subarray(i,i+32768));
   await window.saveQualificationArtifact(name,btoa(binary));
  };
  await save('upload.pem',new TextEncoder().encode(cert));
  const outputs=[];
  for(const versionCode of [1,2]){
   if(versionCode<=identity.versionCodeHighWaterMark)throw Error('Version regression');
   const transformed=transformAabEntries(entries,{applicationId:identity.applicationId,versionCode});
   for(let i=0;i<entries.length;i++)if(!['base/resources.pb','base/manifest/AndroidManifest.xml'].includes(entries[i].name)&&await hash(entries[i].data)!==await hash(transformed[i].data))throw Error('Payload changed');
   identity={...identity,versionCodeHighWaterMark:versionCode};
   const backup=await backupIdentity(identity,password);
   let wrongRejected=false;try{await restoreIdentity(backup,password+'wrong');}catch{wrongRejected=true;}
   if(!wrongRejected)throw Error('Wrong password accepted');
   const restored=await restoreIdentity(backup,password);
   if(JSON.stringify(restored)!==JSON.stringify(identity))throw Error('Identity restore mismatch');
   identity=restored;
   const signed=await signEntries(transformed,identity);
   const aab=writeArchive(signed);
   readArchive(aab);
   const name=versionCode===1?'first.aab':'update.aab';
   await save(name,aab);
   outputs.push({name,versionCode,sha256:await hash(aab),size:aab.length});
  }
  return {browser:navigator.userAgent,secureContext:isSecureContext,applicationId:identity.applicationId,templateSha256:templateHash,entries:entries.length,outputs,backupRestore:true,wrongPasswordRejected:true,certificateUnchanged:identity.certificatePem===cert,elapsedMs:Math.round(performance.now()-start)};
 },{templateHash,templateSize:template.length});
 assert.deepEqual(forbidden,[],'Unexpected network request');
 const run=(exe,args)=>{
  const r=spawnSync(exe,args,{encoding:'utf8',timeout:120000,maxBuffer:16*1024**2});
  assert.equal(r.status,0,(r.stdout||'')+(r.stderr||'')+(r.error||''));return r.stdout+r.stderr;
 };
 const java=join(tools,'jdk-17/bin/java'),keytool=join(tools,'jdk-17/bin/keytool'),jarsigner=join(tools,'jdk-17/bin/jarsigner');
 const trust=join(root,`trust-${Date.now()}.p12`);
 run(keytool,['-importcert','-noprompt','-alias','upload','-file',join(root,'upload.pem'),'-keystore',trust,'-storetype','PKCS12','-storepass','qualification-public-cert']);
 for(const item of result.outputs){
  const path=join(root,item.name);
  const validation=run(java,['-jar',join(tools,'bundletool-1.18.3.jar'),'validate','--bundle='+path]);
  const signature=run(jarsigner,['-verify','-strict','-keystore',trust,'-storepass','qualification-public-cert',path]);
  assert.match(signature,/jar verified/);
  const manifest=run(java,['-jar',join(tools,'bundletool-1.18.3.jar'),'dump','manifest','--bundle='+path]);
  assert.ok(manifest.includes('package="'+result.applicationId+'"'));
  assert.ok(manifest.includes('android:versionCode="'+item.versionCode+'"'));
  await writeFile(join(root,item.name+'.manifest.xml'),manifest);
  item.bundletoolValidated=true;item.jarsignerStrictVerified=true;
 }
 result.networkRequests=requests;result.unexpectedNetworkRequests=forbidden;
 result.scope='Local prepared template; Chromium engine on Linux; not public-release, Play, Safari, Firefox or native OS qualification';
 await writeFile(join(root,'result.json'),JSON.stringify(result,null,2));
 console.log(JSON.stringify(result,null,2));
 await context.close();
}finally{await browser.close();}
