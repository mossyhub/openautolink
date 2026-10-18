// Entry-array validation only. The ZIP reader must validate raw central/local
// headers, CRC, compression, encryption and symlink metadata before this API.
const fail = code => {throw new Error('OAL_PERSONALIZE_'+code);};
const MANIFEST='base/manifest/AndroidManifest.xml', RESOURCES='base/resources.pb';
const OLD='com.openautolink.template';
const needles=[new TextEncoder().encode(OLD), Uint8Array.from([...OLD].flatMap(c=>[c.charCodeAt(0),0])),Uint8Array.from([...OLD].flatMap(c=>[0,c.charCodeAt(0)]))];
export function containsOld(data) {
  return needles.some(needle=>{
    let start=data.indexOf(needle[0]);
    while(start!==-1 && start<=data.length-needle.length) {
      if(needle.every((b,i)=>data[start+i]===b)) return true;
      start=data.indexOf(needle[0],start+1);
    }
    return false;
  });
}
export function validateEntries(entries) {
  if(!Array.isArray(entries) || !entries.length || entries.length>10000) fail('ZIP_LIMIT');
  const names=new Set(),files=new Set(),spellings=new Map(); let total=0;
  for(const entry of entries) {
    if(!entry || !(entry.data instanceof Uint8Array)) fail('ENTRY_TYPE');
    const {name,data}=entry;
    if(typeof name!=='string') fail('ZIP_NAME');
    const parts=name.replace(/\/$/,'').split('/');
    if(!name || name.length>1024 || parts.length>32 || name.includes('//') || /[\\:\x00-\x1f\x7f-\uffff]/.test(name) || parts.some(p=>!p || p==='.' || p==='..' || /[ .]$/.test(p) || /^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i.test(p.split('.')[0]))) fail('ZIP_NAME');
    const key=parts.join('/').toLowerCase(), directory=name.endsWith('/');
    if(names.has(key)) fail('ZIP_ALIAS'); names.add(key);
    for(let i=1;i<=parts.length;i++) {
      const prefix=parts.slice(0,i).join('/'), lower=prefix.toLowerCase();
      if(spellings.has(lower) && spellings.get(lower)!==prefix) fail('ZIP_ALIAS');
      spellings.set(lower,prefix);
    }
    total+=data.length;
    if(data.length>512*1024**2 || total>2*1024**3 || (directory && data.length)) fail('ZIP_LIMIT');
    if(key.split('/').includes('offline-mfi') || /\.(pk8|p7b|p12|pfx|jks|keystore|pem|key)$/.test(key)) fail('CREDENTIAL_INPUT');
    if(/^meta-inf\/[^/]+$/.test(key) && (key==='meta-inf/manifest.mf' || key.startsWith('meta-inf/sig-') || /\.(sf|rsa|dsa|ec)$/.test(key))) fail('SIGNED_INPUT');
    const allowed=name==='BundleConfig.pb' || /^(META-INF|BUNDLE-METADATA)\//.test(name) || [MANIFEST,RESOURCES,'base/assets.pb','base/native.pb'].includes(name) || /^base\/(dex|lib|res|assets|root)\//.test(name) || (directory && ['base/','base/manifest/'].includes(name));
    if(!allowed) fail('MODULE_SHAPE');
    if(!directory) {
      files.add(key);
      if(name.startsWith('base/dex/') && !/^base\/dex\/classes(?:[2-9]|[1-9][0-9]+)?\.dex$/.test(name)) fail('MODULE_SHAPE');
      if(name.startsWith('base/lib/') && !/^base\/lib\/(arm64-v8a|x86_64)\/lib[A-Za-z0-9_.+-]+\.so$/.test(name)) fail('MODULE_SHAPE');
    }
    if([MANIFEST,RESOURCES].includes(name) && data.length>32*1024**2) fail('PROTO_LIMIT');
  }
  for(const name of names) {
    const parts=name.split('/');
    for(let i=1;i<parts.length;i++) if(files.has(parts.slice(0,i).join('/'))) fail('ZIP_ALIAS');
  }
  if(![MANIFEST.toLowerCase(),RESOURCES.toLowerCase(),'bundleconfig.pb'].every(n=>files.has(n)) || ![...files].some(n=>n.startsWith('base/dex/') && n.endsWith('.dex'))) fail('MODULE_SHAPE');
  for(const {name,data} of entries) if(![MANIFEST,RESOURCES].includes(name) && (name.includes(OLD) || containsOld(data))) fail('BAKED_IDENTITY');
}
