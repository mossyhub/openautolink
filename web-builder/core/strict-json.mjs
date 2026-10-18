/** Detect duplicate keys before JSON.parse discards them. No eval or reviver.
 * Strings and numeric syntax are delegated to the platform JSON parser.
 */
export function parseStrictJson(bytes,{maxBytes=65536,maxDepth=32}={}){
 if(!(bytes instanceof Uint8Array)||bytes.length>maxBytes)throw Error('JSON size limit exceeded');
 const text=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(bytes);
 let pos=0;
 const whitespace=()=>{while(/[\t\n\r ]/.test(text[pos]??'!'))pos++;};
 const fail=()=>{throw Error('Malformed JSON metadata');};
 function string(){
  if(text[pos]!== '"')fail();
  const start=pos++;
  while(pos<text.length){
   const c=text[pos++];
   if(c==='"')return JSON.parse(text.slice(start,pos));
   if(c==='\\')pos++;
  }
  fail();
 }
 function value(depth){
  if(depth>maxDepth)throw Error('JSON nesting limit exceeded');
  whitespace();const c=text[pos];
  if(c==='"'){string();return;}
  if(c==='{'||c==='['){
   const object=c==='{',close=object?'}':']',seen=new Set();pos++;whitespace();
   if(text[pos]===close){pos++;return;}
   while(pos<text.length){
    if(object){
     whitespace();const key=string();
     if(seen.has(key))throw Error('Duplicate JSON key');seen.add(key);
     whitespace();if(text[pos++]!==':')fail();
    }
    value(depth+1);whitespace();
    const next=text[pos++];if(next===close)return;if(next!==',')fail();
   }
   fail();
  }
  const start=pos;while(pos<text.length&&!/[\t\n\r ,}\]]/.test(text[pos]))pos++;
  if(start===pos)fail();
  JSON.parse(text.slice(start,pos));
 }
 value(0);whitespace();if(pos!==text.length)fail();
 return JSON.parse(text);
}
