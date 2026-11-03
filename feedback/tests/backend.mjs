// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {DatabaseSync} from 'node:sqlite';
import {handle,validate} from '../functions/api/feedback/_handler.mjs';
const sql=new DatabaseSync(':memory:');sql.exec(readFileSync(new URL('../schema.sql',import.meta.url),'utf8'));
function stmt(query,args=[]){return {bind(...v){return stmt(query,v)},async first(){return sql.prepare(query).get(...args)||null},async run(){const r=sql.prepare(query).run(...args);return {meta:{changes:Number(r.changes)}}}};}
const DB={prepare:stmt,async batch(items){sql.exec('BEGIN');try{const out=[];for(const s of items)out.push(await s.run());sql.exec('COMMIT');return out;}catch(e){sql.exec('ROLLBACK');throw e;}}};
const env={FEEDBACK_DB:DB,FEEDBACK_RATE_SALT:'fixture-salt-only-123456789012345678901234'};
const sample=()=>({schema:1,id:crypto.randomUUID().replaceAll('-',''),version:'0.15.0',edition:'standard',manufacturer:'Fixture',model:'Test TV',firmware:'fixture-build',api:28,reason:'manual',home:false,service:true,events:['CRASH'],frames:['com.pilipala.tvhome.MainActivity.test:12']});
function req(path,body,headers={}){return new Request('https://example.test/api/feedback/'+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json','CF-Connecting-IP':'test-address',...headers},body:body?JSON.stringify(body):undefined});}
let passed=0;function check(value){assert(value);passed++;}
check((await handle(req('config'),{})).status===503);
check((await handle(req('config'),env)).status===200);
check((await handle(req('reports'),env)).status===405);
const p=sample();let response=await handle(req('reports',p),env);check(response.status===201);check((await response.json()).id===p.id);
check(sql.prepare('SELECT count(*) AS n FROM reports').get().n===1);
check((await handle(req('reports',p),env)).status===200);
check(sql.prepare('SELECT count(*) AS n FROM reports').get().n===1);
check((await handle(req('reports',{...p,model:'changed'}),env)).status===409);
check((await handle(req('reports',sample(),{Origin:'https://evil.test'}),env)).status===403);
check((await handle(req('reports',sample(),{'Content-Type':'text/plain'}),env)).status===415);
for(const patch of [{password:'secret'},{events:['PROCESS_STARTED']},{frames:['android.foo.secret:1']},{frames:['com.pilipala.tvhome.Test.a:1 private']},{api:22},{reason:'silent'},{model:'test\nsecret'},{id:'bad'},{events:Array(21).fill('CRASH')},{symptom:'<script>'}]){
 check((await handle(req('reports',{...sample(),...patch}),env)).status===400);
}
check((await handle(req('reports',{...sample(),symptom:'home'}),env)).status===201);
check((await handle(req('status',{id:p.id}),env)).status===200);
check((await handle(req('status',{id:p.id,model:'secret'}),env)).status===400);
sql.prepare("UPDATE reports SET state='investigating' WHERE id=?").run(p.id);
check((await (await handle(req('status',{id:p.id}),env)).json()).state==='investigating');
sql.prepare('UPDATE reports SET expires_at=0 WHERE id=?').run(p.id);
check((await handle(req('status',{id:p.id}),env)).status===404);
await handle(req('reports',sample()),env);
check(!sql.prepare('SELECT id FROM reports WHERE id=?').get(p.id));
for(let i=0;i<30;i++)await handle(req('reports',sample()),env);
check(sql.prepare('SELECT count(*) AS n FROM reports').get().n<=20);
check((await handle(req('reports',sample()),env)).status===429);
check(!JSON.stringify(sql.prepare('SELECT * FROM feedback_limits').all()).includes('test-address'));
const huge=new Request('https://example.test/api/feedback/reports',{method:'POST',headers:{'Content-Type':'application/json'},body:'x'.repeat(12001)});
check((await handle(huge,env)).status===413);
check((await handle(req('reports',sample()),{...env,FEEDBACK_DB:{prepare(){throw Error('offline')}}})).status===503);
console.log('Feedback backend checks passed:',passed);
