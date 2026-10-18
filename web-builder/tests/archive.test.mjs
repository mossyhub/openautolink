import test from 'node:test';
import assert from 'node:assert/strict';
import {zipSync,deflateSync} from 'fflate';
import * as archive from '../core/archive.mjs';
import {execFileSync} from 'node:child_process';

function view(bytes){return new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);}
function central(bytes){return view(bytes).getUint32(bytes.length-6,true);}
function set32(bytes,offset,value){view(bytes).setUint32(offset,value,true);}
function set16(bytes,offset,value){view(bytes).setUint16(offset,value,true);}

// Build an entry with arbitrary raw DEFLATE but CRC/size of the claimed payload.
function rawZip(raw,claimed){
 const zip=zipSync({a:claimed},{level:0}),c=central(zip);
 const result=new Uint8Array(31+raw.length+zip.length-c);
 result.set(zip.subarray(0,31));result.set(raw,31);result.set(zip.subarray(c),31+raw.length);
 const cd=31+raw.length;
 set16(result,8,8);set16(result,cd+10,8);
 set32(result,18,raw.length);set32(result,cd+20,raw.length);
 set32(result,result.length-6,cd);
 return result;
}

test('rejects raw DEFLATE output beyond the claim even with matching prefix CRC',()=>{
 for(const size of [0,1,32]){
  const claimed=new Uint8Array(size),raw=deflateSync(new Uint8Array(1024*1024));
  assert.throws(()=>archive.readArchive(rawZip(raw,claimed)),/expanded size/i);
 }
});

test('keeps raw DEFLATE output allocation bounded for a forged expansion claim',()=>{
 // Fresh realm instruments allocations before fflate captures Uint8Array.
 const zip=rawZip(deflateSync(new Uint8Array(1024*1024)),new Uint8Array(32));
 const script=`
  import assert from 'node:assert/strict';
  const Real=Uint8Array;let active=false,max=0;
  globalThis.Uint8Array=new Proxy(Real,{construct(target,args){
   const result=Reflect.construct(target,args);
   if(active)max=Math.max(max,result.byteLength);
   return result;
  }});
  const {readArchive}=await import(${JSON.stringify(new URL('../core/archive.mjs',import.meta.url).href)});
  const zip=Uint8Array.from(${JSON.stringify(Array.from(zip))});
  active=true;
  assert.throws(()=>readArchive(zip),/expanded size/i);
  assert.ok(max<65536,'unexpected growing inflate allocation: '+max);
 `;
 execFileSync(process.execPath,['--input-type=module','-e',script],{timeout:10000});
});

test('rejects unsafe paths, duplicate names and expansion before extracting',()=>{
 for (const name of ['../evil','/absolute','a\\\\b','a/../b','a//b']) {
  assert.throws(()=>archive.readArchive(zipSync({[name]:new Uint8Array([1])})),/path/i);
 }
 const zip=zipSync({'a':new Uint8Array(100)});
 assert.throws(()=>archive.readArchive(zip,{maxExpandedBytes:99}),/limit/i);
 assert.throws(()=>archive.readArchive(zip,{maxArchiveBytes:1}),/limit/i);
 assert.throws(()=>archive.writeArchive([{name:'a',data:new Uint8Array()},{name:'a',data:new Uint8Array()}]),/duplicate/i);
 const twin=zipSync({'a':new Uint8Array([1]),'b':new Uint8Array([2])});
 for(let i=0;i<twin.length-4;i++)if(twin[i]===0x50&&twin[i+1]===0x4b){
  if(twin[i+2]===1&&twin[i+3]===2)twin[i+46]=97;
  if(twin[i+2]===3&&twin[i+3]===4)twin[i+30]=97;
 }
 assert.throws(()=>archive.readArchive(twin),/duplicate/i);
});

test('rejects corrupt payloads, truncated archives and mismatched local names',()=>{
 const damaged=zipSync({'a':new Uint8Array([1,2,3])},{level:0});
 damaged[31]^=1;
 assert.throws(()=>archive.readArchive(damaged),/CRC/i);
 const truncated=damaged.subarray(0,damaged.length-1);
 assert.throws(()=>archive.readArchive(truncated),/ZIP/i);
 const mismatch=zipSync({'a':new Uint8Array([1])});
 mismatch[30]=98;
 assert.throws(()=>archive.readArchive(mismatch),/mismatch/i);
});

for(const [field,offset] of [['CRC',14],['compressed size',18],['expanded size',22]]){
 test(`rejects local ${field} disagreement before extracting`,()=>{
  const zip=zipSync({a:new Uint8Array([1,2,3])},{level:0});
  set32(zip,offset,view(zip).getUint32(offset,true)+1);
  assert.throws(()=>archive.inspectArchive(zip),/local header mismatch/i);
 });
}

test('rejects stored entries whose central sizes disagree',()=>{
 const zip=zipSync({a:new Uint8Array([1,2,3])},{level:0});
 set32(zip,22,2);set32(zip,central(zip)+24,2);
 assert.throws(()=>archive.inspectArchive(zip),/stored.*size/i);
});

function withDescriptor(signed=true){
 const zip=zipSync({a:new Uint8Array([1,2,3])}),c=central(zip),length=signed?16:12;
 const result=new Uint8Array(zip.length+length);
 result.set(zip.subarray(0,c));result.set(zip.subarray(c),c+length);
 set16(result,6,0x8);set16(result,c+length+8,0x8);
 if(signed)set32(result,c,0x08074b50);
 const p=c+(signed?4:0);
 for(let i=0;i<3;i++){set32(result,p+4*i,view(zip).getUint32(14+4*i,true));set32(result,14+4*i,0);}
 set32(result,result.length-6,c+length);
 return result;
}

test('validates signed and unsigned data descriptors',()=>{
 for(const signed of [true,false]){
  const zip=withDescriptor(signed);
  assert.deepEqual(archive.readArchive(zip),[{name:'a',data:new Uint8Array([1,2,3])}]);
  zip[central(zip)-4]^=1;
  assert.throws(()=>archive.inspectArchive(zip),/descriptor/i);
 }
});

test('rejects a missing data descriptor',()=>{
 const zip=zipSync({a:new Uint8Array([1,2,3])});
 set16(zip,6,8);set16(zip,central(zip)+8,8);
 assert.throws(()=>archive.inspectArchive(zip),/descriptor/i);
});

test('rejects overlapping local entries even when their CRCs and headers agree',()=>{
 const inner=zipSync({b:new Uint8Array([7])},{level:0});
 const payload=inner.subarray(0,central(inner));
 const zip=zipSync({a:payload,b:new Uint8Array([7])},{level:0});
 set32(zip,central(zip)+47+42,31);
 assert.throws(()=>archive.inspectArchive(zip),/overlap/i);
});

for(const mode of [0o120777,0o020600,0o060600,0o010600,0o140600]){
 test(`rejects non-regular ZIP mode ${mode.toString(8)}`,()=>{
  const zip=zipSync({a:new Uint8Array([1])});
  set32(zip,central(zip)+38,mode*65536);
  assert.throws(()=>archive.inspectArchive(zip),/type/i);
 });
}

test('rejects directory mode on a file name',()=>{
 const zip=zipSync({a:new Uint8Array()});set32(zip,central(zip)+38,0o040755*65536);
 assert.throws(()=>archive.inspectArchive(zip),/type/i);
});

test('rejects directory data on input and output',()=>{
 const data=new Uint8Array([1]);
 assert.throws(()=>archive.inspectArchive(zipSync({'a/':data})),/directory.*data/i);
 assert.throws(()=>archive.writeArchive([{name:'a/',data}]),/directory.*data/i);
});

test('enforces per-entry expansion and compression ratio limits',()=>{
 const zip=zipSync({a:new Uint8Array(1024*1024)});
 assert.throws(()=>archive.inspectArchive(zip),/ratio.*limit/i);
 const small=zipSync({a:new Uint8Array(100)});
 assert.throws(()=>archive.inspectArchive(small,{maxEntryBytes:99}),/entry.*limit/i);
 assert.throws(()=>archive.inspectArchive(small,{maxCompressionRatio:1}),/ratio.*limit/i);
});

for(const value of [NaN,Infinity,-1,0.5,'100',undefined]){
 test(`rejects invalid numeric ZIP limits (${String(value)})`,()=>{
  const zip=zipSync({a:new Uint8Array([1])});
  assert.throws(()=>archive.inspectArchive(zip,{maxExpandedBytes:value}),/invalid.*limit/i);
 });
}

test('rejects strong encryption flags without the legacy encryption bit',()=>{
 const zip=zipSync({a:new Uint8Array([1])});
 set16(zip,6,0x40);set16(zip,central(zip)+8,0x40);
 assert.throws(()=>archive.inspectArchive(zip),/encoding/i);
});

test('rejects ZIP64 extra records even without sentinel sizes',()=>{
 const zip=zipSync({a:[new Uint8Array([1]),{extra:{1:new Uint8Array(16)}}]});
 assert.throws(()=>archive.inspectArchive(zip),/ZIP64/i);
});

test('rejects truncated extra field records in central and local headers',()=>{
 for(const local of [true,false]){
  const zip=zipSync({a:[new Uint8Array([1]),{extra:{1234:new Uint8Array(4)}}]});
  const extraStart=local?31:central(zip)+47;
  set16(zip,extraStart+2,5);
  assert.throws(()=>archive.inspectArchive(zip),/extra/i);
 }
});

test('preserves UTF-8 names exactly, including a leading BOM character',()=>{
 const entries=['résumé/文件.bin','😀.txt','\ufeffname'].map(name=>({name,data:new Uint8Array([1,2])}));
 assert.deepEqual(archive.readArchive(archive.writeArchive(entries)),entries);
});

test('rejects invalid UTF-8 names instead of replacement decoding',()=>{
 const zip=zipSync({a:new Uint8Array([1])});
 zip[30]=0xff;zip[central(zip)+46]=0xff;
 assert.throws(()=>archive.readArchive(zip),/encoded data|UTF-8/i);
});

test('rejects file/directory aliases and file parents on read and write',()=>{
 for(const names of [['a','a/'],['a','a/b'],['a/b','a']]){
  const entries=names.map(name=>({name,data:new Uint8Array()}));
  assert.throws(()=>archive.inspectArchive(zipSync(Object.fromEntries(entries.map(e=>[e.name,e.data])))),/alias/i);
  assert.throws(()=>archive.writeArchive(entries),/alias/i);
 }
});

test('accepts empty directories, regular modes, benign extras and sliced input',()=>{
 const zip=zipSync({'d/':[new Uint8Array(),{attrs:0o040755*65536}],
  'd/a':[new Uint8Array([1,2]),{attrs:0o100644*65536,extra:{1234:new Uint8Array(3)}}]});
 const padded=new Uint8Array(zip.length+20);padded.set(zip,10);
 assert.deepEqual(archive.readArchive(padded.subarray(10,10+zip.length)),[
  {name:'d/',data:new Uint8Array()},{name:'d/a',data:new Uint8Array([1,2])}
 ]);
});

test('does not reparse a false end signature inside the archive comment',()=>{
 const zip=zipSync({a:new Uint8Array([1])}),result=new Uint8Array(zip.length+22);
 result.set(zip);set16(result,zip.length-2,22);
 set32(result,zip.length,0x06054b50);set16(result,result.length-2,1);
 assert.deepEqual(archive.readArchive(result),[{name:'a',data:new Uint8Array([1])}]);
});

test('rejects ZIP64 sentinels and local-only ZIP64 extras',()=>{
 for(const offset of [20,24,42]){
  const zip=zipSync({a:new Uint8Array([1])});set32(zip,central(zip)+offset,0xffffffff);
  assert.throws(()=>archive.inspectArchive(zip),/encoding|ZIP64/i);
 }
 for(const offset of [12,16]){
  const zip=zipSync({a:new Uint8Array([1])});set32(zip,zip.length-22+offset,0xffffffff);
  assert.throws(()=>archive.inspectArchive(zip),/ZIP64/i);
 }
 const zip=zipSync({a:[new Uint8Array([1]),{extra:{1234:new Uint8Array(8)}}]});
 set16(zip,31,1);assert.throws(()=>archive.inspectArchive(zip),/ZIP64/i);
});

test('rejects raw DEFLATE truncation, invalid blocks and stored-block overrun',()=>{
 const raw=deflateSync(new Uint8Array(100));
 for(const bad of [raw.subarray(0,raw.length-1),new Uint8Array([7]),deflateSync(new Uint8Array(100),{level:0})]){
  assert.throws(()=>archive.readArchive(rawZip(bad,new Uint8Array(1))));
 }
});

test('reads bounded ZIP entries and writes their exact payload bytes',()=>{
 assert.equal(typeof archive.readArchive,'function');
 const source=zipSync({'base/dex/classes.dex':new Uint8Array([1,2,3])});
 const entries=archive.readArchive(source);
 assert.deepEqual(entries,[{name:'base/dex/classes.dex',data:new Uint8Array([1,2,3])}]);
 assert.deepEqual(archive.readArchive(archive.writeArchive(entries)),entries);
});
