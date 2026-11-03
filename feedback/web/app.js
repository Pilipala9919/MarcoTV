// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
'use strict';
const $=id=>document.getElementById(id);
let report,ready=false,busy=false;
function saved(key){try{return sessionStorage.getItem(key);}catch{return null;}}
function save(key,value){try{sessionStorage.setItem(key,value);}catch{}}
async function request(path,body){
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),15000);
 try{
  const response=await fetch('/api/feedback/'+path,{method:body?'POST':'GET',headers:body?{'Content-Type':'application/json'}:{},body:body?JSON.stringify(body):undefined,cache:'no-store',credentials:'omit',redirect:'error',signal:controller.signal});
  const data=await response.json();if(!response.ok)throw new Error(data.error==='rate_limited'?'提交较频繁，请稍后再试。':'暂未提交，反馈服务暂不可用，请保留此页稍后重试。');return data;
 }finally{clearTimeout(timer);}
}
function update(){ $('submit').disabled=!ready||!report||!$('consent').checked||busy; }
function showReceipt(id){$('number').textContent=id;$('receipt').hidden=false;$('report').hidden=true;}
async function init(){
 try{
  const hash=location.hash.slice(1);
  if(hash){
   if(hash.length>6000)throw new Error('报告链接过长');
   const bytes=Uint8Array.from(atob(hash.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));
   report=JSON.parse(new TextDecoder().decode(bytes));
   if(report.schema!==1||!/^[a-f0-9]{32}$/.test(report.id)||report.reason!=='manual')throw new Error('无效报告');
   save('marcotv-report',JSON.stringify({time:Date.now(),report}));history.replaceState(null,'',location.pathname);
  }else{
   const stored=JSON.parse(saved('marcotv-report')||'null');
   if(stored&&Date.now()-stored.time<1800000)report=stored.report;
  }
  if(report){$('device').textContent=[report.manufacturer,report.model,'Marco TV '+report.version].join(' · ');$('preview').textContent=JSON.stringify(report,null,2);$('symptom').value=report.symptom||'other';$('report').hidden=false;}
  else $('message').textContent='请从电视「维护与帮助 → 本地诊断 → 问题反馈」扫描二维码。';
  const receipt=saved('marcotv-receipt');if(receipt&&/^[a-f0-9]{32}$/.test(receipt)&&(!report||report.id===receipt))showReceipt(receipt);
 }catch{$('message').textContent='无法读取报告，请回到电视重新生成二维码。';report=null;}
 try{const config=await request('config');ready=config.ready===true&&config.schema===1;$('availability').textContent=ready?'反馈服务可用':'反馈服务尚未就绪。';}
 catch{$('availability').textContent='反馈服务暂不可用，报告尚未提交。';}
 update();
}
$('consent').addEventListener('change',update);
$('symptom').addEventListener('change',()=>{if(report){report.symptom=$('symptom').value;$('preview').textContent=JSON.stringify(report,null,2);save('marcotv-report',JSON.stringify({time:Date.now(),report}));}});
$('submit').addEventListener('click',async()=>{
 if(!ready||!report||!$('consent').checked||busy)return;
 busy=true;update();$('message').textContent='正在提交…';
 try{
  const result=await request('reports',report);
  if(result.id!==report.id||!['received','investigating','resolved'].includes(result.state))throw new Error('未收到有效回执，请稍后重试。');
  save('marcotv-receipt',result.id);showReceipt(result.id);$('state').textContent='已收到，等待排查。';$('message').textContent='';
 }catch(e){$('message').textContent=e.message;}finally{busy=false;update();}
});
$('refresh').addEventListener('click',async()=>{
 try{const result=await request('status',{id:$('number').textContent});$('state').textContent=({received:'已收到，等待排查。',investigating:'正在排查。',resolved:'已处理，请检查最新版本。'})[result.state]||'尚无处理状态。';}
 catch{$('state').textContent='暂时无法查询，或反馈编号已过期。';}
});
init();
