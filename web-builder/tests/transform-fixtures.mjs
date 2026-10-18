// Synthetic protobuf fixtures; no credentials, DEX validity, or Android proof.
export const OLD = 'com.openautolink.template';
export const NEW = 'com.example.personal';
export const ANDROID = 'http://schemas.android.com/apk/res/android';
export const MANIFEST = 'base/manifest/AndroidManifest.xml';
export const RESOURCES = 'base/resources.pb';
export const options = {applicationId: NEW, versionCode: 42, versionName: '0.1.528'};
export const enc = s => new TextEncoder().encode(s);
export function cat(...parts) { const out = new Uint8Array(parts.reduce((n,p) => n+p.length,0)); let pos=0; for (const p of parts) {out.set(p,pos); pos+=p.length;} return out; }
export function vint(n) { n=BigInt(n); const out=[]; do {out.push(Number(n & 127n) | (n>127n?128:0)); n>>=7n;} while(n); return Uint8Array.from(out); }
export function field(n, value) { if(typeof value==='string') value=enc(value); return typeof value === 'number' || typeof value === 'bigint' ? cat(vint(n*8),vint(value)) : cat(vint(n*8+2),vint(value.length),value); }
// Noncanonical unknown varint deliberately retained byte-for-byte.
export const unknown = Uint8Array.from([0xa0, 0x06, 0x87, 0x00, 0xad, 0x06, 1, 2, 3, 4]);
export const ids = {name:0x01010003, versionCode:0x0101021b, versionName:0x0101021c, protectionLevel:0x01010009, taskAffinity:0x01010012, authorities:0x01010018, value:0x01010024, enabled:0x0101000e, minSdkVersion:0x0101020c, exported:0x01010010, permission:0x01010006};
export const str = (value, raw=false) => field(raw?3:2, cat(field(1,value),unknown));
export const prim = (value, kind=6) => field(7,cat(field(kind,value),unknown));
export function attr(name,value,{namespace=ANDROID,compiled,resourceId=ids[name]??0}={}) {
 return cat(field(1,namespace),field(2,name),field(3,value),field(5,namespace?resourceId:0),compiled?field(6,compiled):new Uint8Array(),unknown);
}
export function node(name,attrs=[],children=[]) {return cat(field(1,cat(field(3,name),...attrs.map(a=>field(4,a)),...children.map(c=>field(5,c)),unknown)),unknown);}
export function manifest({old=OLD, version=1, extra=[], rootExtra=[], appAttrs=[], rootAttrs=[], permissionAttrs=[], omit=[], compiled=true}={}) {
 const name=(value,raw=false)=>compiled?str(value,raw):undefined;
 return node('manifest', [attr('package',old,{namespace:'',compiled:name(old)}),attr('versionCode',String(version),{compiled:prim(version)}),attr('versionName','0.1.528',{compiled:str('0.1.528')}),...rootAttrs], [
 ...(!omit.includes('permission')?[node('permission',[attr('name',old+'.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION',{compiled:name(old+'.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION')}),attr('protectionLevel','signature',{compiled:prim(2,7)}),...permissionAttrs])]:[]),
 ...(!omit.includes('uses-permission')?[node('uses-permission',[attr('name',old+'.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION',{compiled:name(old+'.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION',true)})])]:[]),
 ...(!omit.includes('application')?[node('application',[attr('name','com.openautolink.app.OalApplication'),...appAttrs],[
 ...(!omit.includes('activity')?[node('activity',[attr('name','androidx.car.app.activity.CarAppActivity'),attr('taskAffinity',old+'.templates',{compiled:name(old+'.templates')})])]:[]),
 ...(!omit.includes('provider')?[node('provider',[attr('name','androidx.startup.InitializationProvider'),attr('authorities',old+'.androidx-startup',{compiled:name(old+'.androidx-startup')})])]:[]),
 node('receiver',[attr('name','com.openautolink.app.diagnostics.SettingsReceiver')],[node('intent-filter',[],[node('action',[attr('name','com.openautolink.app.SET_PREF')])])]),...extra])]:[]), ...rootExtra]);
}
export function resources(old=OLD,id=127) { return cat(field(2,cat(field(1,cat(field(1,id),unknown)),field(2,old),field(3,field(100,'opaque resource type')),unknown)),unknown); }
export function entries(config={}) {return [
 {name:MANIFEST,data:manifest(config)}, {name:RESOURCES,data:resources(config.old??OLD)},
 {name:'BundleConfig.pb',data:field(100,'opaque bundle config')},
 {name:'base/dex/classes.dex',data:enc('dex\nsynthetic-com/openautolink/app')},
 {name:'base/lib/arm64-v8a/libtest.so',data:enc('ELF-synthetic-Java_com_openautolink_app')},
 {name:'META-INF/license.txt',data:enc('keep license')},
 {name:'base/root/META-INF/OTHER.SF',data:enc('nested payload kept')}
 ];}
