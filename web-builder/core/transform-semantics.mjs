// Reviewed vocabulary and Android framework IDs from ProtoPersonalizer.java.
import {parse, one, text, uint} from './transform-wire.mjs';
const ANDROID='http://schemas.android.com/apk/res/android';
const OLD='com.openautolink.template';
const fail=()=>{throw new Error('OAL_PERSONALIZE_UNSUPPORTED_PROTO');};
const check=c=>{if(!c) fail();};
const set=s=>new Set(s.split(' '));
const ATTRS=new Map(Object.entries({
 theme:0x01010000,label:0x01010001,icon:0x01010002,name:0x01010003,
 permission:0x01010006,protectionLevel:0x01010009,enabled:0x0101000e,
 exported:0x01010010,process:0x01010011,taskAffinity:0x01010012,
 excludeFromRecents:0x01010017,authorities:0x01010018,launchMode:0x0101001d,
 screenOrientation:0x0101001e,configChanges:0x0101001f,value:0x01010024,
 resource:0x01010025,minSdkVersion:0x0101020c,versionCode:0x0101021b,
 versionName:0x0101021c,windowSoftInputMode:0x0101022b,targetSdkVersion:0x01010270,
 allowBackup:0x01010280,required:0x0101028e,supportsRtl:0x010103af,
 extractNativeLibs:0x010104ea,directBootAware:0x01010505,networkSecurityConfig:0x01010527,
 roundIcon:0x0101052c,compileSdkVersion:0x01010572,compileSdkVersionCodename:0x01010573,
 appComponentFactory:0x0101057a,foregroundServiceType:0x01010599,dataExtractionRules:0x0101063e
}));
const ROOT=set('uses-sdk uses-permission uses-permission-sdk-23 uses-feature permission queries application supports-screens compatible-screens supports-gl-texture');
const APP=set('activity activity-alias service receiver provider meta-data uses-library uses-native-library profileable property');
const COMPONENTS=set('application activity activity-alias service receiver provider instrumentation');
const ROUTING=set('name permission process taskAffinity authorities appComponentFactory');
const BOOL=set('enabled exported directBootAware allowBackup required supportsRtl extractNativeLibs excludeFromRecents');
function knownElement(parent,name) {
 if(!parent) return name==='manifest';
 if(parent==='/manifest') return ROOT.has(name);
 if(parent==='/manifest/application') return APP.has(name);
 if(parent==='/manifest/queries') return set('intent package provider').has(name);
 if(parent==='/manifest/queries/intent') return set('action category data').has(name);
 if(parent==='/manifest/compatible-screens') return name==='screen';
 if(parent.startsWith('/manifest/application/')) {
  const tail=parent.slice('/manifest/application/'.length);
  if(set('activity activity-alias service receiver provider').has(tail)) return set('intent-filter meta-data property grant-uri-permission path-permission layout').has(name);
  if(tail.endsWith('/intent-filter')) return set('action category data').has(name);
 }
 return false;
}
export function validateElement(fields,parent,name,attributes) {
 check(text(fields,2)==='' && knownElement(parent,name));
 const path=parent+'/'+name;
 const get=n=>attributes.find(a=>text(a,1)===ANDROID && text(a,2)===n);
 const value=n=>{const a=get(n); return a?text(a,3):'';};
 if(path==='/manifest/application') check(value('name')==='com.openautolink.app.OalApplication');
 if(path==='/manifest/permission') {
  check(value('name')===OLD+'.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION');
  check(['2','0x00000002','signature'].includes(value('protectionLevel')));
 }
 const seen=new Set();
 for(const a of attributes) {
  const ns=text(a,1), n=text(a,2), v=text(a,3), id=uint(a,5), alias=ns+'@'+n;
  check(!seen.has(alias)); seen.add(alias);
  if(ns===ANDROID) check(ATTRS.has(n) && id===BigInt(ATTRS.get(n)));
  else check(ns==='' && id===0n && path==='/manifest' && ['package','platformBuildVersionCode','platformBuildVersionName'].includes(n));
  const compiled=one(a,6), item=compiled?parse(compiled.value):null;
  const stringField=item?(one(item,2)??one(item,3)):undefined;
  if(item) {
   check(item.filter(f=>f.number>=1 && f.number<=7).length<=1);
   for(let k=1;k<=7;k++) one(item,k);
   for(const k of [2,3]) {const s=one(item,k); if(s) check(text(parse(s.value),1)===v);}
  }
  const primitive=item?one(item,7):undefined, p=primitive?parse(primitive.value):null;
  if(p) check(p.filter(f=>f.number>=1 && f.number<=14).length<=1);
  if(ns===ANDROID) {
   if(ROUTING.has(n) && item) check(stringField);
   if(BOOL.has(n)) check(p && one(p,8,0) && uint(p,8)===(v==='true'?1n:0n) && ['true','false'].includes(v));
   if(['minSdkVersion','targetSdkVersion','versionCode'].includes(n)) check(p && one(p,6,0) && uint(p,6)>0n && uint(p,6)<=2147483647n && String(uint(p,6))===v);
   const className=(n==='name' && COMPONENTS.has(name)) || n==='appComponentFactory';
   if(className) {check(/^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+$/.test(v)); if(item) check(stringField);}
   if(n==='taskAffinity') check(path==='/manifest/application/activity' && value('name')==='androidx.car.app.activity.CarAppActivity' && v===OLD+'.templates');
   if(n==='authorities' && path.startsWith('/manifest/application/')) check(path==='/manifest/application/provider' && value('name')==='androidx.startup.InitializationProvider' && v===OLD+'.androidx-startup');
  }
  if(path==='/manifest/permission' && n==='protectionLevel' && item) check(p && ((one(p,6,0) && uint(p,6)===2n) || (one(p,7,0) && uint(p,7)===2n)));
 }
}
