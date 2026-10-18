import test from 'node:test';
import assert from 'node:assert/strict';
import {createIdentity,validateIdentity,backupIdentity,restoreIdentity} from '../core/identity.mjs';
import * as api from '../core/version-ledger.mjs';
function fixture(){
 const values=new Map();let chain=Promise.resolve();
 return {storage:{getItem:k=>values.get(k)??null,setItem:(k,v)=>values.set(k,v)},locks:{request:(_name,action)=>{
  const result=chain.then(action);chain=result.catch(()=>{});return result;
 }},values};
}
test('fails closed on missing locks, failed persistence, bad history, changed key and exhausted versions',async()=>{
 const id=await createIdentity('com.example.ledger');
 await assert.rejects(api.reserveVersion(id,0,{storage:fixture().storage,locks:null}),/locking/i);
 for(const v of [-1,1.5,'2',NaN,2100000000])await assert.rejects(api.reserveVersion(id,v,fixture()),/version/i);
 const io=fixture();await api.reserveVersion(id,0,io);
 const key=[...io.values.keys()][0];
 io.values.set(key,'{');await assert.rejects(api.reserveVersion(id,0,io),/ledger/i);
 io.values.set(key,JSON.stringify({formatVersion:1,fingerprint:'wrong',highWaterMark:1}));
 await assert.rejects(api.reserveVersion(id,0,io),/identity|ledger/i);
 const bad=fixture();bad.storage.setItem=()=>{};
 await assert.rejects(api.reserveVersion(id,0,bad),/persist/i);
 const denied=fixture();denied.storage.setItem=()=>{throw Error('quota');};
 await assert.rejects(api.reserveVersion(id,0,denied),/persist/i);
});

test('reserves distinct increasing versions for concurrent builds, old backups and Play history',async()=>{
 assert.equal(typeof api.reserveVersion,'function');
 const identity=await createIdentity('com.example.ledger');
 const io=fixture();
 const [a,b]=await Promise.all([api.reserveVersion(identity,0,io),api.reserveVersion(identity,0,io)]);
 assert.deepEqual([a.versionCode,b.versionCode].sort((x,y)=>x-y),[1,2]);
 assert.equal(identity.versionCodeHighWaterMark,0);
 assert.equal((await api.reserveVersion(identity,10,io)).versionCode,11);
 assert.equal((await api.reserveVersion(identity,0,io)).versionCode,12);
 const persisted=JSON.stringify([...io.values]);
 assert.ok(!persisted.includes('PRIVATE KEY'));assert.ok(!persisted.includes(identity.privateKeyPem));
});

test('template-incompatible restored identities never write a reservation or change the identity',async t=>{
 for(const applicationId of ['com.example.MyCar','com.openautolink.app','com.example.class','com.openautolink.template','com.openautolink','com.'+'a'.repeat(147)]){
  await t.test(applicationId,async()=>{
   const original=await createIdentity(applicationId);
   original.versionCodeHighWaterMark=7;
   const backup=await backupIdentity(original,'disposable preflight password');
   const backupBefore=backup.slice();
   const restored=await restoreIdentity(backup,'disposable preflight password');
   assert.deepEqual(restored,original,'generic recovery must still preserve an incompatible identity');
   assert.equal(await validateIdentity(restored),restored);
   const io=fixture();let writes=0;
   io.storage.setItem=(k,v)=>{writes++;io.values.set(k,v);};
   io.values.set('unrelated-history','retain');
   const before=[...io.values];
   await assert.rejects(api.reserveVersion(restored,10,io),{message:'OAL_PERSONALIZE_IDENTITY'});
   assert.equal(writes,0);
   assert.deepEqual([...io.values],before);
   assert.deepEqual(restored,original);
   assert.deepEqual(backup,backupBefore);
  });
 }
});
