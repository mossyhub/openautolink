import {validateIdentity} from './identity.mjs';
import {validatePersonalApplicationId} from './transform.mjs';
/** Public-only, origin-local high-water ledger. Not a substitute for the latest
 * encrypted backup or reconciliation with Play on another browser/computer.
 * Reservations are retained on cancellation/failure; gaps are safe, reuse is not.
 */
export async function reserveVersion(identity,lastPlayVersion,{storage=globalThis.localStorage,locks=globalThis.navigator?.locks}={}){
 identity=structuredClone(identity);await validateIdentity(identity);
 validatePersonalApplicationId(identity.applicationId);
 if(!locks||typeof locks.request!=='function')throw Error('Browser locking is unavailable; use a supported desktop browser');
 if(!Number.isInteger(lastPlayVersion)||lastPlayVersion<0||lastPlayVersion>=2100000000)throw Error('Invalid or exhausted Play version');
 if(!storage||typeof storage.getItem!=='function'||typeof storage.setItem!=='function')throw Error('Version persistence is unavailable');
 const hash=new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(identity.certificatePem)));
 const fingerprint=Array.from(hash,b=>b.toString(16).padStart(2,'0')).join('');
 const key='oal-version-v1:'+identity.applicationId;
 return locks.request(key,()=>{
  let record;
  try{
   const existing=storage.getItem(key);
   if(existing!==null&&(typeof existing!=='string'||existing.length>1024))throw Error();
   record=existing===null?null:JSON.parse(existing);
   if(existing!==null&&(!record||Object.keys(record).length!==3||record.formatVersion!==1||record.fingerprint!==fingerprint||!Number.isInteger(record.highWaterMark)||record.highWaterMark<1||record.highWaterMark>2100000000))throw Error();
  }catch{throw Error('Version ledger is damaged or belongs to a different signing identity; stop and reconcile your backup');}
  const versionCode=Math.max(identity.versionCodeHighWaterMark,lastPlayVersion,record?.highWaterMark??0)+1;
  if(versionCode>2100000000)throw Error('Android version codes exhausted');
  const next=JSON.stringify({formatVersion:1,fingerprint,highWaterMark:versionCode});
  try{
   storage.setItem(key,next);
   if(storage.getItem(key)!==next)throw Error();
  }catch{throw Error('Could not persist version reservation; no bundle was created');}
  return {versionCode,identity:{...identity,versionCodeHighWaterMark:versionCode}};
 });
}
