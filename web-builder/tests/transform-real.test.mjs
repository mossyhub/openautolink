// Opt-in retained public, unsigned, credential-free template qualification.
// No key access, signing, APK generation, download, or deployment.
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir,mkdtemp,rm} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {readArchive,writeArchive} from '../core/archive.mjs';
import {transformAabEntries} from '../core/transform.mjs';
const template=process.env.OAL_WEB_TEMPLATE, tools=process.env.OAL_WEB_TOOLS;
const here=path.dirname(fileURLToPath(import.meta.url));
const digest=b=>createHash('sha256').update(b).digest('hex');
test('real template: Java protobuf parity, unchanged payload hashes, bundletool validation', {skip:!template||!tools,timeout:180000},async t=>{
 const bytes=new Uint8Array(await readFile(template)), input=readArchive(bytes);
 const opts={applicationId:'com.example.webqualification',versionCode:12345};
 const output=transformAabEntries(input,opts);
 const dir=await mkdtemp(path.join(here,'transform-real-'));
 try {
  for(const n of ['input','java','classes']) await mkdir(path.join(dir,n));
  const mapping={'base/manifest/AndroidManifest.xml':'manifest.pb','base/resources.pb':'resources.pb'};
  for(const e of input) if(mapping[e.name]) await writeFile(path.join(dir,'input',mapping[e.name]),e.data);
  const java=path.join(tools,'jdk-17/bin/java'),javac=path.join(tools,'jdk-17/bin/javac'),jar=path.join(tools,'bundletool-1.18.3.jar');
  const run=(bin,args)=>execFileSync(bin,args,{timeout:120000,stdio:['ignore','pipe','pipe'],maxBuffer:1024*1024});
  run(javac,['-J-Xmx256m','-cp',jar,'-d',path.join(dir,'classes'),path.resolve(here,'../../desktop-builder/oal_builder/resources/ProtoPersonalizer.java')]);
  run(java,['-Xmx256m','-cp',path.join(dir,'classes')+path.delimiter+jar,'ProtoPersonalizer',path.join(dir,'input'),path.join(dir,'java'),'com.openautolink.template',opts.applicationId,String(opts.versionCode)]);
  let changed=0,preserved=0,codeNative=0;
  for(let i=0;i<output.length;i++) {
   const out=output[i], original=input[i]; assert.equal(out.name,original.name);
   if(mapping[out.name]) {
    assert.equal(digest(out.data),digest(await readFile(path.join(dir,'java',mapping[out.name]))),'exact Java bytes '+out.name); changed++;
   } else {assert.equal(digest(out.data),digest(original.data),out.name);preserved++;}
   if(/^base\/(dex|lib)\//.test(out.name)) codeNative++;
  }
  assert.equal(changed,2);assert.equal(output.length,input.length);
  const bundle=path.join(dir,'personalized.aab'); await writeFile(bundle,writeArchive(output));
  run(java,['-Xmx512m','-jar',jar,'validate','--bundle='+bundle]);
  assert.equal(digest(await readFile(template)),digest(bytes),'template not modified');
  t.diagnostic(JSON.stringify({templateSha256:digest(bytes),members:input.length,changed,preserved,codeNative,javaExactByteParity:true,bundletoolValidate:true}));
 } finally { await rm(dir,{recursive:true,force:true}); }
});
