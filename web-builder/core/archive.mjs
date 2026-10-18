import {inflateSync,zipSync} from 'fflate';
const defaults={maxArchiveBytes:512*1024**2,maxExpandedBytes:768*1024**2,maxEntries:20000,maxEntryBytes:512*1024**2,maxCompressionRatio:1000};
const decoder=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true});
const crcTable=Uint32Array.from({length:256},(_,n)=>{
 let crc=n;for(let bit=0;bit<8;bit++)crc=(crc&1)?0xedb88320^(crc>>>1):crc>>>1;
 return crc>>>0;
});
function crc32(data){
 let crc=0xffffffff;for(const byte of data)crc=crcTable[(crc^byte)&255]^(crc>>>8);
 return (crc^0xffffffff)>>>0;
}
function safeName(name){
 const parts=name.replace(/\/$/,'').split('/');
 if(!name || name.length>1024 || /[\\\x00-\x1f\x7f:]/.test(name) || parts.some(p=>!p||p==='.'||p==='..'))throw Error('Unsafe ZIP path');
}
function checkAliases(names){
 // Names are case-sensitive UTF-8 archive keys, not host filesystem paths.
 for(const name of names){
  if(name.endsWith('/')&&names.has(name.slice(0,-1)))throw Error('ZIP path alias');
  for(let p=name.indexOf('/');p>=0;p=name.indexOf('/',p+1)){
   if(names.has(name.slice(0,p)))throw Error('ZIP path alias');
  }
 }
}
export function inspectArchive(bytes,limits={}){
 const cap={...defaults,...limits};
 for(const key of Object.keys(defaults))if(!Number.isSafeInteger(cap[key])||cap[key]<0)throw Error('Invalid ZIP limit');
 if(!(bytes instanceof Uint8Array)||bytes.length<22)throw Error('Invalid ZIP');
 if(bytes.length>cap.maxArchiveBytes)throw Error('Archive size limit exceeded');
 const view=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);
 const u16=p=>view.getUint16(p,true),u32=p=>view.getUint32(p,true);
 const checkExtra=(p,length)=>{
  const stop=p+length;
  while(p<stop){
   if(p+4>stop)throw Error('Truncated ZIP extra field');
   const id=u16(p),size=u16(p+2);p+=4;
   if(p+size>stop)throw Error('Truncated ZIP extra field');
   if(id===1)throw Error('ZIP64 not supported');
   p+=size;
  }
 };
 let end=-1;
 for(let p=bytes.length-22;p>=Math.max(0,bytes.length-65557);p--)if(u32(p)===0x06054b50&&p+22+u16(p+20)===bytes.length){end=p;break;}
 if(end<0)throw Error('Invalid ZIP directory');
 const count=u16(end+10),size=u32(end+12),start=u32(end+16);
 if(u16(end+4)||u16(end+6)||u16(end+8)!==count||count===65535||size===0xffffffff||start===0xffffffff)throw Error('Multi-disk and ZIP64 not supported');
 if(count>cap.maxEntries||start+size!==end)throw Error('ZIP directory limit or boundary mismatch');
 let cursor=start,total=0;const records=[],seen=new Set();
 for(let i=0;i<count;i++){
  if(cursor+46>end||u32(cursor)!==0x02014b50)throw Error('Invalid ZIP entry');
  const flags=u16(cursor+8),method=u16(cursor+10),compressed=u32(cursor+20),expanded=u32(cursor+24),n=u16(cursor+28),extra=u16(cursor+30),comment=u16(cursor+32),offset=u32(cursor+42);
  if(cursor+46+n+extra+comment>end)throw Error('Truncated ZIP directory');
  checkExtra(cursor+46+n,extra);
  if(flags&(1|0x40)||![0,8].includes(method)||compressed===0xffffffff||expanded===0xffffffff||offset===0xffffffff||u16(cursor+34))throw Error('Unsupported ZIP entry encoding');
  if(method===0&&compressed!==expanded)throw Error('ZIP stored size mismatch');
  const name=decoder.decode(bytes.subarray(cursor+46,cursor+46+n));safeName(name);
  const type=(u32(cursor+38)>>>16)&0xf000;
  if(![0,0x8000,0x4000].includes(type)||(type===0x4000&&!name.endsWith('/')))throw Error('Unsupported ZIP entry type');
  if(name.endsWith('/')&&expanded)throw Error('ZIP directory has data');
  if(seen.has(name))throw Error('Duplicate ZIP entry');seen.add(name);
  if(expanded>cap.maxEntryBytes)throw Error('ZIP entry size limit exceeded');
  if(expanded/Math.max(1,compressed)>cap.maxCompressionRatio)throw Error('ZIP compression ratio limit exceeded');
  total+=expanded;if(total>cap.maxExpandedBytes)throw Error('Expanded size limit exceeded');
  if(offset+30>start||u32(offset)!==0x04034b50)throw Error('Invalid ZIP local header');
  const localNameLength=u16(offset+26),localExtra=u16(offset+28),dataStart=offset+30+localNameLength+localExtra;
  if(dataStart+compressed>start||u16(offset+6)!==flags||u16(offset+8)!==method||decoder.decode(bytes.subarray(offset+30,offset+30+localNameLength))!==name)throw Error('ZIP local header mismatch');
  checkExtra(offset+30+localNameLength,localExtra);
  for(const [local,central] of [[14,16],[18,20],[22,24]]){
   const value=u32(offset+local);
   if(value!==u32(cursor+central)&&(!(flags&8)||value!==0))throw Error('ZIP local header mismatch');
  }
  const crc=u32(cursor+16);let dataEnd=dataStart+compressed;
  if(flags&8){
   // Descriptors may omit the signature; try both forms (a CRC can equal it).
   const matches=p=>p+12<=start&&u32(p)===crc&&u32(p+4)===compressed&&u32(p+8)===expanded;
   if(dataEnd+4<=start&&u32(dataEnd)===0x08074b50&&matches(dataEnd+4))dataEnd+=16;
   else if(matches(dataEnd))dataEnd+=12;
   else throw Error('ZIP data descriptor mismatch');
  }
  records.push({name,expanded,compressed,offset,dataStart,dataEnd,method,crc});
  cursor+=46+n+extra+comment;
 }
 if(cursor!==end)throw Error('ZIP directory count mismatch');
 checkAliases(seen);
 const ordered=[...records].sort((a,b)=>a.offset-b.offset);
 for(let i=1;i<ordered.length;i++)if(ordered[i].offset<ordered[i-1].dataEnd)throw Error('Overlapping ZIP entries');
 return records;
}
export function readArchive(bytes,limits={}){
 const records=inspectArchive(bytes,limits);
 return records.map(({name,expanded,compressed,dataStart,method,crc})=>{
  const source=bytes.subarray(dataStart,dataStart+compressed);
  // fflate 0.8.3 never resizes inflateSync's explicit `out` buffer. Its
  // returned view silently clamps excess output, so reserve one extra byte
  // to detect overrun (even when the claimed prefix has a matching CRC).
  // Do not use unzipSync: it reparses metadata and allocates exactly the claim.
  // This bounds output allocation, not CPU: fflate still walks the stream.
  const data=method===0?source.slice():inflateSync(source,{out:new Uint8Array(expanded+1)});
  if(data.length!==expanded)throw Error('ZIP expanded size mismatch');
  if(crc32(data)!==crc)throw Error('ZIP CRC mismatch');
  return {name,data};
 });
}
export function writeArchive(entries){
 const seen=new Set(),files=Object.create(null);let total=0;
 for(const {name,data} of entries){
  safeName(name);if(seen.has(name))throw Error('Duplicate ZIP entry');seen.add(name);
  if(!(data instanceof Uint8Array))throw Error('Invalid entry bytes');
  if(name.endsWith('/')&&data.length)throw Error('ZIP directory has data');
  total+=data.length;if(total>defaults.maxExpandedBytes||seen.size>defaults.maxEntries)throw Error('ZIP output limit exceeded');
  files[name]=data;
 }
 checkAliases(seen);
 return zipSync(files,{level:6});
}
