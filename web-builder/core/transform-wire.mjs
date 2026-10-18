// Minimal wire editor, not a generated protobuf serializer. Untouched fields keep
// their original tag, length, value, order and unknown-field representation.
const fail = () => { throw new Error('OAL_PERSONALIZE_UNSUPPORTED_PROTO'); };
const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', {fatal:true, ignoreBOM:true});
export const utf8 = s => encoder.encode(s);
export function join(parts) {
  const out = new Uint8Array(parts.reduce((n,p)=>n+p.length,0));
  let offset=0;
  for(const p of parts) {out.set(p,offset); offset+=p.length;}
  return out;
}
function varint(n) {
  n=BigInt(n); const bytes=[];
  do {bytes.push(Number(n&127n) | (n>127n?128:0)); n>>=7n;} while(n);
  return Uint8Array.from(bytes);
}
export function parse(data) {
  let pos=0; const fields=[];
  function read() {
    let n=0n;
    for(let i=0;i<10;i++) {
      if(pos>=data.length) fail();
      const b=data[pos++];
      if(i===9 && b>1) fail();
      n |= BigInt(b&127)<<BigInt(i*7);
      if(!(b&128)) return n;
    }
    fail();
  }
  while(pos<data.length) {
    const start=pos, key=read();
    if(key>0xffffffffn || (key>>3n)===0n) fail();
    const number=Number(key>>3n), wire=Number(key&7n), tag=data.subarray(start,pos);
    let value;
    if(wire===0) value=read();
    else {
      let length;
      if(wire===2) {const n=read(); if(n>BigInt(data.length-pos)) fail(); length=Number(n);}
      else if(wire===1) length=8;
      else if(wire===5) length=4;
      else fail(); // Proto3 has no groups; fail closed instead of discarding them.
      if(length>data.length-pos) fail();
      value=data.subarray(pos,pos+length); pos+=length;
    }
    fields.push({number,wire,value,tag,raw:data.subarray(start,pos)});
    if(fields.length>200000) fail();
  }
  return fields;
}
export function all(fields,n,wire=2) {
  const values=fields.filter(f=>f.number===n);
  if(values.some(f=>f.wire!==wire)) fail();
  return values;
}
export function one(fields,n,wire=2) {
  const values=all(fields,n,wire);
  if(values.length>1) fail();
  return values[0];
}
export function text(fields,n) {
  const f=one(fields,n);
  try {return f?decoder.decode(f.value):'';} catch {fail();}
}
export function uint(fields,n) {return one(fields,n,0)?.value??0n;}
export function replace(f,value) {
  if(typeof value==='string') value=utf8(value);
  return f.wire===0 ? join([f.tag,varint(value)]) : join([f.tag,varint(value.length),value]);
}
export function edit(fields,changes) {
  return join(fields.map(f=>{
    if(!changes.has(f)) return f.raw;
    const value=changes.get(f);
    // Do not canonicalize any enclosing length when the child is unchanged.
    if(value instanceof Uint8Array && f.value instanceof Uint8Array && value.length===f.value.length && value.every((b,i)=>b===f.value[i])) return f.raw;
    return replace(f,value);
  }));
}
