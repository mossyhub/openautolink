import test from 'node:test';
import assert from 'node:assert/strict';
import * as api from '../core/strict-json.mjs';
const enc=s=>new TextEncoder().encode(s);
test('metadata parser rejects malformed UTF8, BOM, malformed JSON and bounded nesting',()=>{
 for(const s of ['', '[1,]', '{"x":1,}', '{"x":01}', '{"x":undefined}', '{}{}', '\ufeff{}', '"unclosed', '{"x":"bad\n"}'])assert.throws(()=>api.parseStrictJson(enc(s)));
 assert.throws(()=>api.parseStrictJson(new Uint8Array([0xff])));
 assert.throws(()=>api.parseStrictJson(enc('{}'),{maxBytes:1}),/size/i);
 assert.throws(()=>api.parseStrictJson(enc('[[[0]]]'),{maxDepth:2}),/nesting/i);
 assert.deepEqual(api.parseStrictJson(enc('{"__proto__":{"x":1}}')),JSON.parse('{"__proto__":{"x":1}}'));
});
test('strict metadata parser preserves JSON values and rejects escaped duplicate keys',()=>{
 assert.equal(typeof api.parseStrictJson,'function');
 const text='{"x":[true,false,null,-1.25e2,"escaped \\u0041"],"nested":{"a":1}}';
 assert.deepEqual(api.parseStrictJson(enc(text)),JSON.parse(text));
 for(const input of ['{"a":1,"a":2}','{"a":1,"\\u0061":2}','{"x":{"a":1,"a":2}}'])assert.throws(()=>api.parseStrictJson(enc(input)),/duplicate/i);
});
