import {validateElement} from './transform-semantics.mjs';
import {validateEntries, containsOld} from './transform-entries.mjs';
import {parse, one, all, text, uint, edit} from './transform-wire.mjs';
const OLD_ID = 'com.openautolink.template';
const MANIFEST = 'base/manifest/AndroidManifest.xml';
const RESOURCES = 'base/resources.pb';
const RESERVED = new Set('abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null _'.split(' '));
function fail(code) { throw new Error('OAL_PERSONALIZE_' + code); }
function requireProto(condition) { if (!condition) fail('UNSUPPORTED_PROTO'); }
export function validatePersonalApplicationId(id) {
  if (typeof id !== 'string' || id.length > 150 || !/^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$/.test(id) || id === 'com.openautolink.app' || id.split('.').some(p => RESERVED.has(p)) || id.includes(OLD_ID) || OLD_ID.includes(id)) fail('IDENTITY');
}
function validateOptions(options) {
  const {applicationId: id, versionCode: version} = options ?? {};
  validatePersonalApplicationId(id);
  if (!Number.isInteger(version) || version < 1 || version > 2100000000) fail('VERSION');
}
const ANDROID = 'http://schemas.android.com/apk/res/android';
function rewriteAttribute(fields, replacement, numeric) {
  const value = one(fields,3);
  requireProto(value);
  const changes = new Map([[value,replacement]]);
  const compiled = one(fields,6);
  if(compiled) {
    const item = parse(compiled.value);
    const kind = numeric ? 7 : one(item,2) ? 2 : 3;
    const inner = one(item,kind);
    requireProto(inner);
    const innerFields = parse(inner.value);
    const leaf = one(innerFields,numeric?6:1,numeric?0:2);
    requireProto(leaf);
    changes.set(compiled,edit(item,new Map([[inner,edit(innerFields,new Map([[leaf,numeric?BigInt(replacement):replacement]]))]])));
  }
  return edit(fields,changes);
}
function rewriteManifest(data,options) {
  const seen=new Set(); let applications=0;
  function rewriteNode(data,parent) {
    const node=parse(data), element=one(node,1), nodeText=one(node,2);
    requireProto(!(element && nodeText));
    // Java Character.isWhitespace (String.isBlank) excludes NBSP and BOM.
    if(!element) {requireProto(!/[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(text(node,2))); return data;}
    const fields=parse(element.value), name=text(fields,3), path=parent+'/'+name, changes=new Map();
    const attrFields=all(fields,4), attributes=attrFields.map(f=>parse(f.value));
    validateElement(fields,parent,name,attributes);
    if(path==='/manifest/application') applications++;
    if(path==='/manifest' && options.versionName!==undefined) {
      const versionName=attributes.find(a=>text(a,1)===ANDROID && text(a,2)==='versionName');
      if(typeof options.versionName!=='string' || !versionName || text(versionName,3)!==options.versionName) fail('VERSION_NAME');
    }
    for(let i=0;i<attrFields.length;i++) {
      const f=attrFields[i], a=attributes[i], namespace=text(a,1), attrName=text(a,2), value=text(a,3);
      const key=path+'@'+attrName;
      let replacement;
      let numeric=false;
      if(key==='/manifest@package' && namespace==='') {requireProto(value===OLD_ID); replacement=options.applicationId;}
      else if(namespace===ANDROID) {
        if(key==='/manifest@versionCode') { replacement=String(options.versionCode); numeric=true; }
        else if(['/manifest/permission@name','/manifest/uses-permission@name'].includes(key) && value===OLD_ID+'.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION') replacement=options.applicationId+'.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION';
        else if(key==='/manifest/application/activity@taskAffinity' && value===OLD_ID+'.templates') replacement=options.applicationId+'.templates';
        else if(key==='/manifest/application/provider@authorities' && value===OLD_ID+'.androidx-startup') replacement=options.applicationId+'.androidx-startup';
      }
      if(replacement!==undefined) {
        requireProto(!seen.has(key)); seen.add(key);
        changes.set(f,rewriteAttribute(a,replacement,numeric));
      }
    }
    for(const child of all(fields,5)) changes.set(child,rewriteNode(child.value,path));
    return edit(node,new Map([[element,edit(fields,changes)]]));
  }
  const result=rewriteNode(data,'');
  requireProto(seen.size===6 && applications===1 && !containsOld(result));
  return result;
}
function rewriteResources(data,options) {
  const table=parse(data), pkg=one(table,2);
  requireProto(pkg);
  const fields=parse(pkg.value), name=one(fields,2);
  requireProto(name && text(fields,2)===OLD_ID);
  const id=one(fields,1);
  requireProto(id && uint(parse(id.value),1)===127n);
  const result=edit(table,new Map([[pkg,edit(fields,new Map([[name,options.applicationId]]))]]));
  requireProto(!containsOld(result));
  return result;
}
export function transformAabEntries(entries, options) {
  validateOptions(options);
  validateEntries(entries);
  return entries.map(({name,data})=>({name,data:name===MANIFEST?rewriteManifest(data,options):name===RESOURCES?rewriteResources(data,options):data}));
}
