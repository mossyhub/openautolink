import test from 'node:test';
import assert from 'node:assert/strict';
import {transformAabEntries} from '../core/transform.mjs';
import {entries,options,OLD,NEW,MANIFEST,RESOURCES,enc,cat,field,unknown,attr,node,prim,str,manifest,resources} from './transform-fixtures.mjs';
test('rejects trailing line terminators in application IDs and component classes',()=>{
 for(const end of ['\n','\r','\u2028','\u2029']) {
  assert.throws(()=>transformAabEntries(entries(),{...options,applicationId:NEW+end}),{message:'OAL_PERSONALIZE_IDENTITY'});
  reject({extra:[node('service',[attr('name','com.example.Service'+end)])]});
 }
});
test('text nodes use Java isBlank semantics, not JavaScript trim',()=>{
 for(const value of ['\u00a0','\ufeff','\u2007','\u202f']) reject({rootExtra:[field(2,value)]});
 assert.doesNotThrow(()=>transformAabEntries(entries({rootExtra:[field(2,'\u001c\u2000\n')]}),options));
});
const reject=config=>assert.throws(()=>transformAabEntries(entries(config),options),{message:'OAL_PERSONALIZE_UNSUPPORTED_PROTO'});
test('preserves noncanonical length encoding in an entirely unedited subtree',()=>{
 const element=cat(field(3,'queries'),unknown);
 const queries=cat(Uint8Array.from([10,element.length|128,0]),element,unknown);
 const config={rootExtra:[queries]};
 assert.ok(Buffer.from(transformAabEntries(entries(config),options)[0].data).equals(Buffer.from(manifest({...config,old:NEW,version:42}))));
});
for(const [label,config] of [
 ['nonblank text',{rootExtra:[field(2,'not XML whitespace')]}],
 ['node competing oneof',{rootExtra:[cat(node('queries'),field(2,'text after element'))]}],
 ['compiled routing competing oneof',{appAttrs:[attr('permission','com.example.permission',{compiled:cat(str('com.example.permission'),prim(1))})]}],
 ['primitive competing oneof',{appAttrs:[attr('enabled','true',{compiled:field(7,cat(field(8,1),field(6,0)))})]}],
 ['compiled string duplicate value',{appAttrs:[attr('permission','com.example.permission',{compiled:field(2,cat(field(1,'com.example.permission'),field(1,'com.example.permission')))})]}],
 ['root element duplicate',{rootExtra:[cat(node('queries'),node('queries'))]}],
 ]) test('reject ambiguous protobuf '+label,()=>reject(config));
for(const data of [Uint8Array.from([0]),Uint8Array.from([10,255]),Uint8Array.from([10,2,255]),Uint8Array.from([0x80,0x80,0x80,0x80,0x80,0x80,0x80,0x80,0x80,0x80,1]),Uint8Array.from([0xa3,6,0xa4,6])]) test('reject malformed protobuf '+Buffer.from(data).toString('hex'),()=>{
 const input=entries().map(e=>e.name===MANIFEST?{...e,data}:e);
 assert.throws(()=>transformAabEntries(input,options),{message:'OAL_PERSONALIZE_UNSUPPORTED_PROTO'});
});
test('reject known fields with wrong wire type and invalid UTF-8',()=>{
 for(const custom of [field(1,field(3,5)),field(1,field(3,Uint8Array.from([0xff])))]) {
  const input=entries().map(e=>e.name===MANIFEST?{...e,data:custom}:e);
  assert.throws(()=>transformAabEntries(input,options),{message:'OAL_PERSONALIZE_UNSUPPORTED_PROTO'});
 }
});
test('reject bad signature protection even when raw value is valid',()=>reject({omit:['permission'],rootExtra:[node('permission',[attr('name',OLD+'.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'),attr('protectionLevel','signature',{compiled:prim(1,7)})])]}));
