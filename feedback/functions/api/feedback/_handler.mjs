// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
const MAX_BYTES=12000, DAYS=30*86400000;
export const events=new Set(['CRASH','UI_UNRESPONSIVE_SUSPECTED','PREVIOUS_RUN_INTERRUPTED_UNKNOWN','HOME_AUTO_SETUP_INCOMPLETE','HOME_AUTO_RESTORE_INCOMPLETE','HOME_AUTO_COMPAT_UNCONNECTED','HOME_AUTO_SERVICE_REBIND_REQUESTED','HOME_AUTO_NATIVE_SELECTED','HOME_AUTO_COMPAT_CONNECTED','WAKE_HOME_RESTORED']);
const allowed=new Set(['schema','id','version','edition','manufacturer','model','firmware','api','reason','home','service','events','frames','symptom']);
function invalid(){throw new Error('invalid_report');}
export function validate(p){
 if(!p||Array.isArray(p)||Object.keys(p).some(k=>!allowed.has(k))||p.schema!==1)invalid();
 if(!/^[a-f0-9]{32}$/.test(p.id)||!/^\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?$/.test(p.version))invalid();
 if(!['standard','restricted'].includes(p.edition)||!['manual','automatic'].includes(p.reason))invalid();
 if(p.symptom!==undefined&&!['home','startup','tasks','appearance','network','other'].includes(p.symptom))invalid();
 for(const k of ['manufacturer','model','firmware'])if(typeof p[k]!=='string'||p[k].length>100||!/^[\p{L}\p{N} ._()+,:/-]*$/u.test(p[k]))invalid();
 if(!Number.isInteger(p.api)||p.api<23||p.api>100||typeof p.home!=='boolean'||typeof p.service!=='boolean')invalid();
 if(!Array.isArray(p.events)||p.events.length>20||p.events.some(e=>!events.has(e)))invalid();
 if(!Array.isArray(p.frames)||p.frames.length>12||p.frames.some(f=>typeof f!=='string'||!/^com\.pilipala\.tvhome\.[A-Za-z0-9_.$]+:[0-9]{1,6}$/.test(f)))invalid();
 return p;
}
function json(data,status=200){return new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});}
async function digest(value){return [...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value)))].map(x=>x.toString(16).padStart(2,'0')).join('');}
export async function handle(request,env){
 const path=new URL(request.url).pathname;
 const ready=!!env.FEEDBACK_DB&&typeof env.FEEDBACK_RATE_SALT==='string'&&env.FEEDBACK_RATE_SALT.length>=32;
 if(path==='/api/feedback/config'&&request.method==='GET'){
  if(!ready)return json({ready:false,schema:1},503);
  try{await env.FEEDBACK_DB.prepare('SELECT id FROM reports LIMIT 1').first();return json({ready:true,schema:1,statusDays:30,cleanup:'on-submit'});}
  catch{return json({ready:false,schema:1},503);}
 }
 if(!ready)return json({error:'temporarily_unavailable'},503);
 if(!['/api/feedback/reports','/api/feedback/status'].includes(path))return json({error:'not_found'},404);
 if(request.method!=='POST')return json({error:'method_not_allowed'},405);
 const origin=request.headers.get('Origin');
 if(origin&&origin!==new URL(request.url).origin)return json({error:'origin_rejected'},403);
 if(!request.headers.get('Content-Type')?.startsWith('application/json'))return json({error:'json_required'},415);
 // Read bounded bytes even when Content-Length is missing or dishonest.
 let input='',size=0;
 try{
  const reader=request.body?.getReader();if(!reader)return json({error:'invalid_report'},400);
  const decoder=new TextDecoder();
  while(true){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>MAX_BYTES){await reader.cancel();return json({error:'too_large'},413);}input+=decoder.decode(value,{stream:true});}
  input+=decoder.decode();
 }catch{return json({error:'invalid_report'},400);}
 let p;try{p=JSON.parse(input);}catch{return json({error:'invalid_report'},400);}
 try{
  const now=Date.now();
  if(path.endsWith('/status')){
   if(!p||Object.keys(p).length!==1||typeof p.id!=='string'||!/^[a-f0-9]{32}$/.test(p.id))return json({error:'invalid_report'},400);
   const row=await env.FEEDBACK_DB.prepare('SELECT state FROM reports WHERE id=? AND expires_at>?').bind(p.id,now).first();
   return row?json({id:p.id,state:row.state}):json({error:'not_found'},404);
  }
  try{validate(p);}catch{return json({error:'invalid_report'},400);}
  const previous=await env.FEEDBACK_DB.prepare('SELECT state,payload FROM reports WHERE id=? AND expires_at>?').bind(p.id,now).first();
  if(previous)return previous.payload===JSON.stringify(p)?json({id:p.id,state:previous.state}):json({error:'id_conflict'},409);
  // Hourly pseudonymous rate bucket; raw IP is never written to the database.
  const hour=Math.floor(now/3600000),ip=request.headers.get('CF-Connecting-IP')||'unknown';
  const bucket=await digest(env.FEEDBACK_RATE_SALT+'|'+hour+'|'+ip);
  const result=await env.FEEDBACK_DB.batch([
   env.FEEDBACK_DB.prepare('DELETE FROM reports WHERE expires_at<=?').bind(now),
   env.FEEDBACK_DB.prepare('DELETE FROM feedback_limits WHERE expires_at<=?').bind(now),
   env.FEEDBACK_DB.prepare('INSERT INTO feedback_limits(bucket,n,expires_at) VALUES(?,1,?) ON CONFLICT(bucket) DO UPDATE SET n=n+1').bind(bucket,now+7200000),
   env.FEEDBACK_DB.prepare("INSERT OR IGNORE INTO reports(id,created_at,expires_at,state,payload) SELECT ?,?,?, 'received',? WHERE (SELECT n FROM feedback_limits WHERE bucket=?)<=20").bind(p.id,now,now+DAYS,JSON.stringify(p),bucket)
  ]);
  if(!result[3].meta.changes)return json({error:'rate_limited'},429);
  return json({id:p.id,state:'received'},201);
 }catch{return json({error:'temporarily_unavailable'},503);}
}
