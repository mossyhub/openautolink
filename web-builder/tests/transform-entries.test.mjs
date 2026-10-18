import test from 'node:test';
import assert from 'node:assert/strict';
import {transformAabEntries} from '../core/transform.mjs';
import {entries,options,enc,MANIFEST,RESOURCES,OLD} from './transform-fixtures.mjs';
const reject=(input,code)=>assert.throws(()=>transformAabEntries(input,options),{message:'OAL_PERSONALIZE_'+code});
for(const name of ['', '/base/assets/x','base/assets/../x','base//assets/x','base/assets/CON.txt','base/assets/nul','base/assets/x.','base/assets/x ','base/assets/x\\y','base/assets/x:y','base/assets/x\0z','base/assets/é','base/assets/'+ 'x'.repeat(1025)]) test('reject unsafe entry '+JSON.stringify(name.slice(0,50)),()=>reject([...entries(),{name,data:enc('')}],'ZIP_NAME'));
for(const name of ['META-INF/MANIFEST.MF','META-INF/UPLOAD.SF','META-INF/UPLOAD.RSA','META-INF/upload.ec','META-INF/SIG-custom']) test('reject signed template '+name,()=>reject([...entries(),{name,data:enc('signature')}],'SIGNED_INPUT'));
for(const name of ['base/assets/offline-mfi/x','base/assets/secret.key','base/assets/private.PK8','BUNDLE-METADATA/test.jks','base/assets/cert.p7b']) test('reject credential entry '+name,()=>reject([...entries(),{name,data:enc('secret')}],'CREDENTIAL_INPUT'));
for(const name of ['feature/manifest/AndroidManifest.xml','base/dex/classes1.dex','base/lib/armeabi-v7a/libx.so','base/other.pb']) test('reject unreviewed module '+name,()=>reject([...entries(),{name,data:enc('')}],'MODULE_SHAPE'));
for(const name of [MANIFEST,RESOURCES,'BundleConfig.pb','base/dex/classes.dex']) test('requires member '+name,()=>reject(entries().filter(e=>e.name!==name),'MODULE_SHAPE'));
test('rejects duplicate, case aliased, file/directory aliases and prefix spellings',()=>{
 for(const extra of [
  [{name:MANIFEST,data:enc('')}],
  [{name:'base/assets/Hello',data:enc('')},{name:'base/assets/hello',data:enc('')}],
  [{name:'base/assets/X/a',data:enc('')},{name:'base/assets/x/b',data:enc('')}],
  [{name:'base/assets/x',data:enc('')},{name:'base/assets/x/y',data:enc('')}],
  [{name:'base/assets/x',data:enc('')},{name:'base/assets/x/',data:enc('')}]
 ]) reject([...entries(),...extra],'ZIP_ALIAS');
});
test('enforces expanded entry shape and count limits',()=>{
 reject([],'ZIP_LIMIT'); reject(new Array(10001).fill(entries()[0]),'ZIP_LIMIT');
 reject([...entries(),{name:'base/assets/x',data:'not bytes'}],'ENTRY_TYPE');
 reject([...entries(),{name:'base/assets/x/',data:enc('not empty')}],'ZIP_LIMIT');
 reject(entries().map(e=>e.name===MANIFEST?{...e,data:new Uint8Array(32*1024*1024+1)}:e),'PROTO_LIMIT');
});
test('rejects old identity baked into payload names or UTF-8 / UTF-16 bytes',()=>{
 const utf16=Array.from(OLD).flatMap(c=>[c.charCodeAt(0),0]);
 for(const data of [enc(OLD),Uint8Array.from(utf16),Uint8Array.from(utf16.map((_,i)=>utf16[i^1]))]) reject([...entries(),{name:'base/assets/x',data}],'BAKED_IDENTITY');
 reject([...entries(),{name:'base/assets/'+OLD,data:enc('')}],'BAKED_IDENTITY');
});
