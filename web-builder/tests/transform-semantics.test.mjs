import test from 'node:test';
import assert from 'node:assert/strict';
import {transformAabEntries} from '../core/transform.mjs';
import {entries, options, OLD, NEW, ANDROID, MANIFEST, RESOURCES, enc,cat,field,unknown,attr,node,prim,str,manifest,resources} from './transform-fixtures.mjs';
const reject=config=>assert.throws(()=>transformAabEntries(entries(config),options),{message:'OAL_PERSONALIZE_UNSUPPORTED_PROTO'});
for(const omit of ['application','permission','uses-permission','activity','provider']) test('requires reviewed identity '+omit,()=>reject({omit:[omit]}));
for(const [label,config] of [
 ['wrong source ID',{old:'com.example.other'}],
 ['unknown element',{extra:[node('unreviewed')]}],
 ['relative class',{extra:[node('service',[attr('name','.Relative')])]}],
 ['compiled class reference',{extra:[node('service',[attr('name','com.example.Service',{compiled:field(1,field(1,12))})])]}],
 ['unknown attribute',{appAttrs:[attr('sharedUserId','system')]}],
 ['wrong android resource ID',{appAttrs:[attr('enabled','true',{resourceId:1,compiled:prim(1,8)})]}],
 ['non-android namespace',{appAttrs:[attr('enabled','true',{namespace:'urn:evil',compiled:prim(1,8)})]}],
 ['unnamespaced component name',{extra:[node('service',[attr('name','com.example.Service',{namespace:''})])]}],
 ['boolean mismatch',{appAttrs:[attr('enabled','true',{compiled:prim(0,8)})]}],
 ['boolean integer',{appAttrs:[attr('enabled','true',{compiled:prim(1)})]}],
 ['missing boolean compiled value',{appAttrs:[attr('enabled','true')]}],
 ['SDK compiled mismatch',{rootExtra:[node('uses-sdk',[attr('minSdkVersion','29',{compiled:prim(28)})])]}],
 ['SDK invalid decimal',{rootExtra:[node('uses-sdk',[attr('minSdkVersion','0',{compiled:prim(0)})])]}],
 ['duplicate attribute',{appAttrs:[attr('name','com.openautolink.app.OalApplication')]}],
 ['compiled string mismatch',{appAttrs:[attr('permission','com.example.permission',{compiled:str('com.example.other')})]}],
 ['routing resource reference',{appAttrs:[attr('permission','com.example.permission',{compiled:field(1,field(1,127))})]}],
 ['wrong task affinity owner',{extra:[node('activity',[attr('name','com.example.Other'),attr('taskAffinity',OLD+'.templates')])]}],
 ['unreviewed authority',{extra:[node('provider',[attr('name','com.example.Other'),attr('authorities','com.example.other')])]}],
 ['nonpositive template version',{version:0}],
 ['duplicate application',{rootExtra:[node('application',[attr('name','com.openautolink.app.OalApplication')])]}],
 ['duplicate rewrite path',{rootExtra:[node('uses-permission',[attr('name',OLD+'.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION')])]}],
 ['old ID in unreviewed manifest value',{extra:[node('meta-data',[attr('name','unreviewed'),attr('value',OLD)])]}],
 ]) test('reject manifest '+label,()=>reject(config));
test('valid boolean and SDK primitives remain unchanged',()=>{
 const config={appAttrs:[attr('enabled','true',{compiled:prim(1,8)})],rootExtra:[node('uses-sdk',[attr('minSdkVersion','29',{compiled:prim(29)})])]};
 assert.deepEqual(transformAabEntries(entries(config),options)[0].data,manifest({...config,old:NEW,version:42}));
});
test('versionName is a preserve-only assertion matching the desktop contract',()=>{
 assert.throws(()=>transformAabEntries(entries(),{...options,versionName:'invented'}),{message:'OAL_PERSONALIZE_VERSION_NAME'});
 assert.throws(()=>transformAabEntries(entries(),{...options,versionName:123}),{message:'OAL_PERSONALIZE_VERSION_NAME'});
 assert.doesNotThrow(()=>transformAabEntries(entries(),{applicationId:NEW,versionCode:2}));
});
for(const [label,data] of [['wrong package',resources('com.example.other')],['wrong package ID',resources(OLD,128)],['baked resource ID',cat(resources(),field(100,OLD))],['two packages',cat(resources(),resources())],['no package ID',field(2,field(2,OLD))]]) test('reject resource '+label,()=>{
 const input=entries().map(e=>e.name===RESOURCES?{...e,data}:e);
 assert.throws(()=>transformAabEntries(input,options),{message:'OAL_PERSONALIZE_UNSUPPORTED_PROTO'});
});
