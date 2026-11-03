// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const script=readFileSync(new URL('../web/app.js',import.meta.url),'utf8');
let passed=0;const check=v=>{assert(v);passed++;};
const data=id=>({schema:1,id,version:'0.15.0',edition:'standard',manufacturer:'Fixture',model:'<script>test</script>',firmware:'fixture',api:28,reason:'manual',home:false,service:false,events:[],frames:[]});
async function page(report,storage=new Map(),failure=false,privateMode=false){
 const elements=new Map();
 const el=id=>{if(!elements.has(id))elements.set(id,{hidden:['report','receipt'].includes(id),disabled:false,checked:false,textContent:'',value:'other',listeners:{},addEventListener(n,fn){this.listeners[n]=fn;}});return elements.get(id);};
 const location={hash:report?'#'+Buffer.from(JSON.stringify(report)).toString('base64url'):'',pathname:'/feedback/'};
 let writes=0;
 const context=vm.createContext({document:{getElementById:el},location,history:{replaceState(){location.hash='';}},sessionStorage:{getItem(k){if(privateMode)throw Error('disabled');return storage.get(k)||null;},setItem(k,v){if(privateMode)throw Error('disabled');storage.set(k,v);}},atob:s=>Buffer.from(s,'base64').toString('binary'),TextDecoder,Uint8Array,AbortController,setTimeout,clearTimeout,
  async fetch(url,options){
   if(url.endsWith('config'))return {ok:true,json:async()=>({ready:true,schema:1})};
   writes++;if(failure)return {ok:false,json:async()=>({error:'temporarily_unavailable'})};
   return {ok:true,json:async()=>({id:JSON.parse(options.body).id,state:'received'})};
  }});
 vm.runInContext(script,context);await new Promise(resolve=>setImmediate(resolve));
 return {el,location,storage,writes:()=>writes};
}
const first=data('a'.repeat(32));let p=await page(first);
check(p.writes()===0);check(p.el('submit').disabled);check(p.el('preview').textContent.includes('<script>test</script>'));check(p.location.hash==='');
p.el('consent').checked=true;p.el('consent').listeners.change();check(!p.el('submit').disabled);
await p.el('submit').listeners.click();check(p.writes()===1);check(!p.el('receipt').hidden);check(p.el('number').textContent===first.id);
let q=await page(data('b'.repeat(32)),p.storage);check(q.el('receipt').hidden);check(!q.el('report').hidden);check(q.el('submit').disabled);
q.el('symptom').value='home';q.el('symptom').listeners.change();q=await page(null,q.storage);check(q.el('symptom').value==='home');
q=await page(data('c'.repeat(32)),new Map(),true);q.el('consent').checked=true;q.el('consent').listeners.change();await q.el('submit').listeners.click();check(q.el('receipt').hidden);check(q.el('message').textContent.includes('暂未提交'));
q=await page(first,new Map(),false,true);check(!q.el('report').hidden);q.el('consent').checked=true;q.el('consent').listeners.change();await q.el('submit').listeners.click();check(!q.el('receipt').hidden);
console.log('Feedback web checks passed:',passed);
