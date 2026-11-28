/* Copyright (C) 2026 Pilipala · GPL-3.0-only */
'use strict';
const $=id=>document.getElementById(id);
let previewKey="";
let state=null,csrf='',pending=false,tab='desktop',draft={},previewTimer,previewChain=Promise.resolve(),image=null,videoFile=null,videoObjectUrl='',report='',pairTimer,drag=null,editVersion=null,initialTabApplied=false,initialCardApplied=false;
const queryParams=new URLSearchParams(location.search);
const queryTab=queryParams.get('tab');
const queryCard=queryParams.get('card')||'';
function selectTab(next){if(!next||!$(next))return;tab=next;document.querySelectorAll('.tab').forEach(s=>s.hidden=s.id!==tab);document.querySelectorAll('[data-tab]').forEach(n=>{if(n.dataset.tab===tab)n.setAttribute('aria-current','page');else n.removeAttribute('aria-current');});window.scrollTo(0,0);}
function el(tag,text,cls){const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;}
function toast(text){$('toast').textContent=text;$('toast').hidden=false;clearTimeout(toast.timer);toast.timer=setTimeout(()=>$('toast').hidden=true,4500);}
async function request(path,body){const opts={credentials:'same-origin',cache:'no-store',headers:{}};if(body!==undefined){opts.method='POST';opts.headers['Content-Type']='application/json';opts.headers['X-Marco-Csrf']=csrf;opts.body=JSON.stringify(body);}const res=await fetch(path,opts);const text=await res.text();let data;try{data=JSON.parse(text);}catch{data={error:text};}if(!res.ok)throw new Error(data.error||'操作未完成，请重试');return data;}
async function refresh(render=true){try{const next=await request('/state');csrf=next.csrf;if(state&&next.revision!==state.revision&&Object.keys(draft).length){draft={};editVersion=null;toast('电视上的设置已变化，未保存预览已取消');}state=next;connected();if(render&&!drag&&!$('sheet').open)draw();}catch(e){$('connection').textContent='连接中断 · 请检查电视与网络';throw e;}}
function findTileById(id){if(!state||!id)return null;for(const row of (state.rows||[]))for(const t of (row.tiles||[]))if(t.id===id)return t;for(const t of (state.hidden||[]))if(t.id===id)return t;return null;}
function connected(){$('pair').hidden=true;$('connected').hidden=false;$('tabs').hidden=false;$('heading').textContent='把桌面整理成你的样子。';$('connection').textContent=state.model+' · 已连接';if(!initialTabApplied){initialTabApplied=true;const wanted=queryTab||state.initialTab;if(wanted&&$(wanted))selectTab(wanted);}if(!initialCardApplied){const wantedCard=queryCard||state.initialCard;if(wantedCard){const target=findTileById(wantedCard);if(target){initialCardApplied=true;setTimeout(()=>cardSheet(target),60);}}}}
async function act(data){if(pending)return;pending=true;try{const next=await request('/action',{revision:state.revision,...data});state=next;draw();return true;}catch(e){toast(e.message);await refresh().catch(()=>{});return false;}finally{pending=false;}}
function draw(){drawRows();drawClockModes();drawSliders();drawWeather();drawWallpapers();$('sort').value=state.sort;$('device').textContent=state.model+' · Marco TV '+state.version+' · 可用空间 '+state.freeMB+' MB';$('previewStatus').textContent=state.preview?'正在电视预览 · 尚未保存':'当前为已保存设置';if(state.cast&&$('castNow'))$('castNow').textContent=state.cast.playing?('正在电视投屏：'+(state.cast.title||'投屏画面')+'（'+(state.cast.state==='PAUSED_PLAYBACK'?'已暂停':'播放中')+'）'):('DLNA 投屏设备名：'+(state.cast.deviceName||'Marco TV')+' · 视频 App 点击 TV 图标即可直接投屏');}
function drawClockModes(){const box=$('clockModeBox');if(!box||!state.clockModes)return;box.replaceChildren();const wrap=el('div',undefined,'panel');wrap.append(el('h3','时间字体颜色快捷预设'));const row=el('div',undefined,'chips');const saved=state.settings.find(s=>s.key==='clock_color_mode')?.value??0;const active=draft.clock_color_mode!==undefined?draft.clock_color_mode:saved;state.clockModes.forEach((name,idx)=>{const b=el('button',name,idx===active?'chip active':'chip');b.onclick=()=>{if(editVersion===null)editVersion=state.revision;previewKey='clock_color_mode';draft.clock_color_mode=idx;$('previewStatus').textContent='正在电视预览 · 尚未保存';drawClockModes();sendPreview();};row.append(b);});wrap.append(row);box.append(wrap);}
function drawWeather(){const box=$('weatherBox');if(!box||!state.weather)return;const w=state.weather;box.replaceChildren();const sec=el('div',undefined,'panel');sec.append(el('h3','桌面天气模块'));sec.append(el('p',w.cityLabel+' · '+w.summary,'muted'));const topBtns=el('div',undefined,'rowBtns');const tog=el('button',w.enabled?'关闭桌面天气':'开启桌面天气',w.enabled?'':'primary');tog.onclick=()=>act({op:'weather',field:'enabled',value:!w.enabled});const ref=el('button','探测并刷新');ref.onclick=()=>act({op:'weather',field:'refresh'});topBtns.append(tog,ref);sec.append(topBtns);const srcL=el('label','数据来源');const srcSel=el('select');(w.sources||[]).forEach((name,i)=>{const o=document.createElement('option');o.value=String(i);o.textContent=name;if(i===w.source)o.selected=true;srcSel.append(o);});srcSel.onchange=()=>act({op:'weather',field:'source',value:Number(srcSel.value)});srcL.append(srcSel);sec.append(srcL);const pL=el('label','降雨概率显示');const pSel=el('select');(w.precipModes||[]).forEach((name,i)=>{const o=document.createElement('option');o.value=String(i);o.textContent=name;if(i===w.precipMode)o.selected=true;pSel.append(o);});pSel.onchange=()=>act({op:'weather',field:'precip_mode',value:Number(pSel.value)});pL.append(pSel);sec.append(pL);const chkRow=el('div',undefined,'chips');for(const [labelText,field,cur] of [['天气图标','show_icon',w.showIcon],['彩色图标','colorful',w.colorful],['实时温度','show_current',w.showCurrent],['温度范围','show_range',w.showRange],['城市名','show_city',w.showCity],['屏保天气','ambient',w.ambientWeather]]){const cb=el('button',(cur?'✓ ':'')+labelText,cur?'chip active':'chip');cb.onclick=()=>act({op:'weather',field,value:!cur});chkRow.append(cb);}sec.append(el('p','显示内容','muted'),chkRow);const cities=el('div',undefined,'chips');for(const c of (w.presets||[])){const b=el('button',c.name,'chip');b.onclick=()=>act({op:'weather',field:'preset',value:c.key});cities.append(b);}sec.append(el('p','快速切换城市（无需位置权限）','muted'),cities);box.append(sec);}
function drawWallpapers(){const box=$('myWallpapers');if(!box)return;box.replaceChildren();const list=state.wallpapers||[];if(!list.length){box.append(el('p','暂未导入自定义壁纸，可在上方上传照片或 MP4 动态壁纸。','muted'));return;}for(const w of list){const row=el('div',undefined,'wpRow');const label=(w.video?'[动态] ':'')+w.title+(w.active?'（使用中）':'');button(row,label,()=>act({op:'wallpaper_entry',id:w.id}));const del=el('button','删除','danger');del.onclick=()=>act({op:'wallpaper_delete',id:w.id});row.append(del);box.append(row);}}
function tile(t,group){const box=el('div',undefined,'tile');box.dataset.id=t.id;const face=el('button',undefined,'face');const art=el('span',undefined,'art');const img=el('img');img.src='/art?id='+encodeURIComponent(t.id)+'&v='+(state?state.revision:0);img.alt='';img.loading='lazy';img.onerror=()=>{img.remove();art.textContent=t.name.slice(0,6);};art.append(img);const nameEl=el('span',t.name,'name');if(t.width>0||t.height>0||t.depth>=0||t.radius>=0)nameEl.append(el('span','自定义','sizeTag'));face.append(art,nameEl);face.onclick=()=>cardSheet(t);box.append(face);
 if(group){const handle=el('button','⠿','handle');handle.setAttribute('aria-label','拖动或调整 '+t.name+' 的顺序');box.append(handle);handle.onclick=e=>{if(e.detail===0)moveSheet(t,group);};handle.onpointerdown=e=>{if(pending||drag)return;e.preventDefault();handle.setPointerCapture(e.pointerId);drag={box,group,startX:e.clientX,startY:e.clientY,target:null,moved:false};box.classList.add('dragging');};handle.onpointermove=e=>{if(!drag||drag.box!==box)return;drag.moved=drag.moved||Math.hypot(e.clientX-drag.startX,e.clientY-drag.startY)>6;const target=document.elementFromPoint(e.clientX,e.clientY)?.closest('.tile');document.querySelectorAll('.drop').forEach(n=>n.classList.remove('drop'));if(target&&target.parentNode===box.parentNode&&target!==box){drag.target=target;target.classList.add('drop');}};const end=async(e,cancel)=>{if(!drag||drag.box!==box)return;const d=drag;drag=null;box.classList.remove('dragging');document.querySelectorAll('.drop').forEach(n=>n.classList.remove('drop'));if(!cancel&&d.moved&&d.target){const row=state.rows.find(r=>r.id===group);const ids=row.tiles.map(t=>t.id);const from=ids.indexOf(t.id),to=ids.indexOf(d.target.dataset.id);ids.splice(from,1);ids.splice(to,0,t.id);await act({op:'reorder',group,ids});}else if(!cancel&&!d.moved)moveSheet(t,group);};handle.onpointerup=e=>end(e,false);handle.onpointercancel=e=>end(e,true);}
 return box;}
function drawRows(){const root=$('rows');root.replaceChildren();for(const row of state.rows){const title=el('h3',row.name),grid=el('div',undefined,'grid');for(const t of row.tiles)grid.append(tile(t,row.id));root.append(title,grid);if(!row.tiles.length)grid.append(el('p','暂无卡片，可从“添加应用”选择。'));}const h=$('hidden');h.replaceChildren();for(const t of state.hidden){const b=el('button','恢复 '+t.name,'option');b.onclick=()=>act({op:'card',id:t.id,field:'hidden',value:false});h.append(b);}}
function sheet(title){$('sheetTitle').textContent=title;$('sheetBody').replaceChildren();if(!$('sheet').open)$('sheet').showModal();return $('sheetBody');}
$('closeSheet').onclick=()=>$('sheet').close();
function button(parent,title,fn){const b=el('button',title,'option');b.onclick=fn;parent.append(b);return b;}
function cardSheet(t){
 const latest=findTileById(t.id)||t;
 const defs=(state&&state.cardDefaults)||{width:100,height:100,depth:0,radius:32};
 const body=sheet((latest.kind===1?'信号源 · ':'')+latest.name);
 const cur={width:latest.width||0,height:latest.height||0,depth:latest.depth!==undefined?latest.depth:-1,radius:latest.radius!==undefined?latest.radius:-1};
 const effW=()=>cur.width>0?cur.width:defs.width;
 const effH=()=>cur.height>0?cur.height:defs.height;
 // 1. Cover image upload & cropper
 const coverSec=el('div',undefined,'sheetSection');
 coverSec.append(el('h3','封面图片'));
 const previewImg=el('img',undefined,'coverPreview');
 previewImg.src='/art?id='+encodeURIComponent(latest.id)+'&v='+(state?state.revision:0);
 previewImg.alt=latest.name;
 previewImg.onerror=()=>{previewImg.hidden=true;};
 coverSec.append(previewImg);
 const fileLabel=el('label','从手机相册选择图片上传','file');
 const fileInput=el('input');
 fileInput.type='file';
 fileInput.accept='image/jpeg,image/png,image/webp';
 fileLabel.append(fileInput);
 coverSec.append(fileLabel);
 const cropBox=el('div');
 cropBox.hidden=true;
 const cropCanvas=el('canvas',undefined,'cardCropCanvas');
 const cZoomL=el('label','放大'),cZoom=el('input');cZoom.type='range';cZoom.min=100;cZoom.max=250;cZoom.value=100;cZoomL.append(cZoom);
 const cXL=el('label','水平位置'),cX=el('input');cX.type='range';cX.min=0;cX.max=100;cX.value=50;cXL.append(cX);
 const cYL=el('label','垂直位置'),cY=el('input');cY.type='range';cY.min=0;cY.max=100;cY.value=50;cYL.append(cY);
 const uploadBtn=el('button','上传并设为卡片封面','primary');
 uploadBtn.style.width='100%';
 const prog=el('progress');prog.max=100;prog.value=0;prog.hidden=true;
 const statusP=el('p','','muted');
 cropBox.append(cropCanvas,cZoomL,cXL,cYL,uploadBtn);
 coverSec.append(cropBox,prog,statusP);
 let chosenImg=null;
 function renderCardCrop(){
  if(!chosenImg)return;
  const aspect=(3.2*effW())/(1.8*effH());
  let cw,ch;
  if(aspect>=1){cw=960;ch=Math.max(200,Math.round(960/aspect));}
  else{ch=960;cw=Math.max(200,Math.round(960*aspect));}
  cropCanvas.width=cw;cropCanvas.height=ch;
  const ctx=cropCanvas.getContext('2d');
  const scale=Math.max(cw/chosenImg.width,ch/chosenImg.height)*Number(cZoom.value)/100;
  const w=chosenImg.width*scale,h=chosenImg.height*scale;
  const x=(cw-w)*Number(cX.value)/100,y=(ch-h)*Number(cY.value)/100;
  ctx.fillStyle='#1a2534';ctx.fillRect(0,0,cw,ch);ctx.drawImage(chosenImg,x,y,w,h);
 }
 fileInput.onchange=()=>{
  const f=fileInput.files[0];if(!f)return;
  if(f.size>50*1024*1024){toast('请选择小于 50 MB 的图片');return;}
  const url=URL.createObjectURL(f),imgObj=new Image();
  imgObj.onload=()=>{
   URL.revokeObjectURL(url);
   if(imgObj.naturalWidth*imgObj.naturalHeight>100000000){toast('图片像素过大，请在相册缩小后重试');return;}
   chosenImg=imgObj;cropBox.hidden=false;cZoom.value=100;cX.value=50;cY.value=50;renderCardCrop();
   statusP.textContent='裁切比例已自动匹配当前卡片尺寸';
  };
  imgObj.onerror=()=>{URL.revokeObjectURL(url);toast('图片无法读取，请选择 JPEG、PNG 或 WebP');};
  imgObj.src=url;
 };
 for(const sl of [cZoom,cX,cY])sl.oninput=renderCardCrop;
 uploadBtn.onclick=()=>{
  if(!chosenImg)return;
  uploadBtn.disabled=true;
  cropCanvas.toBlob(async blob=>{
   try{
    if(!blob)throw new Error('图片处理失败');
    prog.hidden=false;prog.value=0;statusP.textContent='正在上传到电视…';
    state=await upload('/upload-cover',blob,n=>prog.value=n,{'X-Marco-Card-Id':encodeURIComponent(latest.id)});
    draw();
    previewImg.hidden=false;
    previewImg.src='/art?id='+encodeURIComponent(latest.id)+'&v='+state.revision;
    cropBox.hidden=true;chosenImg=null;fileInput.value='';
    statusP.textContent='已上传并应用到电视';
    toast('封面已应用到电视');
   }catch(e){statusP.textContent=e.message;await refresh(false).catch(()=>{});}
   finally{uploadBtn.disabled=false;prog.hidden=true;}
  },'image/jpeg',.92);
 };
 const presetRow=el('div',undefined,'presetRow');
 for(const [labelText,styleKey] of [['原始图标','original'],['午夜蓝','blue'],['森林绿','green'],['暖琥珀','amber'],['雾紫','purple']]){
  const pb=el('button',labelText);
  pb.onclick=async()=>{if(await act({op:'card',id:latest.id,field:'cover',value:styleKey})){toast('已更新封面样式');setTimeout(()=>{refresh().then(()=>{const fresh=findTileById(latest.id);if(fresh&&$('sheet').open)cardSheet(fresh);}).catch(()=>{});},500);}};
  presetRow.append(pb);
 }
 coverSec.append(presetRow);
 body.append(coverSec);
 // 2. Card dimensions (width / height / depth / radius)
 const sizeSec=el('div',undefined,'sheetSection');
 sizeSec.append(el('h3','卡片长宽高与圆角'));
 function makeSizeSlider(title,min,max,getVal,setVal,unit,isCustom){
  const l=el('label',title),vSpan=el('span','','value'),sl=el('input');
  sl.type='range';sl.min=min;sl.max=max;sl.step=1;sl.value=getVal();
  function syncText(){vSpan.textContent=sl.value+unit+(isCustom()?'':'（跟随全局）');}
  syncText();
  sl.oninput=()=>{setVal(Number(sl.value));syncText();renderCardCrop();};
  l.append(vSpan,sl);sizeSec.append(l);
  return {sl,syncText};
 }
 const sW=makeSizeSlider('宽度',60,160,effW,v=>cur.width=v,'%',()=>cur.width>0);
 const sH=makeSizeSlider('高度',60,150,effH,v=>cur.height=v,'%',()=>cur.height>0);
 const sD=makeSizeSlider('厚度（立体深度）',0,100,()=>cur.depth>=0?cur.depth:defs.depth,v=>cur.depth=v,'%',()=>cur.depth>=0);
 const sR=makeSizeSlider('圆角',0,90,()=>cur.radius>=0?cur.radius:defs.radius,v=>cur.radius=v,'',()=>cur.radius>=0);
 const sizeBtns=el('div',undefined,'rowBtns');
 const applySizeBtn=el('button','应用卡片尺寸','primary');
 applySizeBtn.onclick=async()=>{
  if(await act({op:'card',id:latest.id,field:'size',width:cur.width,height:cur.height,depth:cur.depth,radius:cur.radius})){
   toast('卡片尺寸已即时生效');
  }
 };
 const resetSizeBtn=el('button','恢复跟随全局');
 resetSizeBtn.onclick=async()=>{
  cur.width=0;cur.height=0;cur.depth=-1;cur.radius=-1;
  sW.sl.value=defs.width;sH.sl.value=defs.height;sD.sl.value=defs.depth;sR.sl.value=defs.radius;
  sW.syncText();sH.syncText();sD.syncText();sR.syncText();renderCardCrop();
  if(await act({op:'card',id:latest.id,field:'size',width:0,height:0,depth:-1,radius:-1})){
   toast('已恢复跟随全局尺寸');
  }
 };
 sizeBtns.append(applySizeBtn,resetSizeBtn);
 sizeSec.append(sizeBtns);
 body.append(sizeSec);
 // 3. Name & card actions
 const metaSec=el('div',undefined,'sheetSection');
 metaSec.append(el('h3','名称与管理'));
 const label=el('label','卡片名称'),input=el('input');input.value=latest.name;input.maxLength=60;label.append(input);metaSec.append(label);
 button(metaSec,'保存名称',async()=>{if(await act({op:'card',id:latest.id,field:'name',value:input.value})){$('sheetTitle').textContent=(latest.kind===1?'信号源 · ':'')+(input.value.trim()||latest.name);toast('名称已保存');}});
 button(metaSec,latest.pinned?'取消置顶':'置顶此卡片',async()=>{if(await act({op:'card',id:latest.id,field:'pinned',value:!latest.pinned}))$('sheet').close();});
 button(metaSec,'隐藏卡片',async()=>{if(await act({op:'card',id:latest.id,field:'hidden',value:true}))$('sheet').close();});
 button(metaSec,'恢复默认外观（名称 / 封面 / 尺寸）',async()=>{if(await act({op:'card',id:latest.id,field:'reset'})){$('sheet').close();toast('已恢复默认外观');}});
 body.append(metaSec);
}
function moveSheet(t,group){const body=sheet('调整顺序 · '+t.name);for(const [title,delta] of [['向前移动',-1],['向后移动',1]])button(body,title,async()=>{const row=state.rows.find(r=>r.id===group),ids=row.tiles.map(a=>a.id),i=ids.indexOf(t.id),j=i+delta;if(j<0||j>=ids.length){toast('已经到边缘了');return;}[ids[i],ids[j]]=[ids[j],ids[i]];if(await act({op:'reorder',group,ids}))$('sheet').close();});}
$('sort').onchange=()=>act({op:'sort',mode:$('sort').value});
function drawSliders(){if(Object.keys(draft).length)return;const root=$('sliders');root.replaceChildren();for(const s of state.settings){const label=el('label',s.label),value=el('span',String(s.value),'value'),slider=el('input');slider.type='range';slider.min=s.min;slider.max=s.max;slider.step=1;slider.value=s.value;label.append(value,slider);root.append(label);slider.oninput=()=>{if(editVersion===null)editVersion=state.revision;previewKey=s.key;draft[s.key]=Number(slider.value);value.textContent=slider.value;$('previewStatus').textContent='正在电视预览 · 尚未保存';mini();clearTimeout(previewTimer);previewTimer=setTimeout(sendPreview,140);};slider.onchange=()=>{clearTimeout(previewTimer);sendPreview();};}mini();}
function mini(){const val=k=>draft[k]??state.settings.find(s=>s.key===k)?.value;document.querySelectorAll('.miniCards div').forEach(n=>{n.style.borderRadius=Math.round(val('card_radius')/3)+'px';});$('miniClock').style.fontSize=Math.round(val('clock_size')*.38)+'px';}
function sendPreview(){const values={...draft},rev=editVersion,key=previewKey;if(!Object.keys(values).length)return previewChain;previewChain=previewChain.then(async()=>{try{const next=await request('/action',{op:'preview',revision:rev,values,key});state=next;}catch(e){draft={};editVersion=null;toast(e.message);await refresh().catch(()=>{});}});return previewChain;}
$('save').onclick=async()=>{clearTimeout(previewTimer);await sendPreview();if(await act({op:'save'})){draft={};editVersion=null;draw();toast('外观已保存到电视');}};
$('cancel').onclick=async()=>{clearTimeout(previewTimer);await previewChain;draft={};editVersion=null;await act({op:'cancel'});};
$('add').onclick=async()=>{const body=sheet('添加应用');body.append(el('p','正在读取电视已安装应用…'));try{const data=await request('/apps');body.replaceChildren();const input=el('input');input.placeholder='搜索名称或包名';const list=el('div');body.append(input,list);function render(){list.replaceChildren();for(const a of data.apps.filter(a=>(a.name+' '+a.id).toLowerCase().includes(input.value.toLowerCase())))button(list,a.name,()=>chooseEntry(a));}input.oninput=render;render();}catch(e){body.replaceChildren(el('p',e.message));}};
async function chooseEntry(app){const body=sheet('选择启动入口 · '+app.name);body.append(el('p','正在检查可用页面…'));try{const data=await request('/entries?id='+encodeURIComponent(app.id));body.replaceChildren();if(!data.entries.length)body.append(el('p',data.message||'此应用没有可用公开入口'));for(const e of data.entries)button(body,e.name+' · '+e.component.split('/').pop(),async()=>{if(await act({op:'add',component:e.component})){$('sheet').close();toast('已加入我的应用');setTimeout(()=>refresh().catch(()=>{}),800);}});}catch(e){body.replaceChildren(el('p',e.message));}}
for(const [i,name] of ['雾蓝海岸','暖沙丘','极夜山脊','峡谷'].entries())button($('builtins'),name,()=>act({op:'wallpaper',index:i}));
$('photo').onchange=async()=>{const file=$('photo').files[0];if(!file)return;if(file.size>50*1024*1024){toast('请选择小于 50 MB 的图片');return;}if($('photoName'))$('photoName').value=file.name.replace(/\.[^.]+$/,'').slice(0,28);const url=URL.createObjectURL(file),next=new Image();next.onload=()=>{URL.revokeObjectURL(url);if(next.naturalWidth*next.naturalHeight>100000000){toast('图片像素过大，请在相册缩小后重试');return;}image=next;$('crop').hidden=false;$('zoom').value=100;$('cropX').value=50;$('cropY').value=50;crop();};next.onerror=()=>{URL.revokeObjectURL(url);toast('图片无法读取，请选择 JPEG、PNG 或 WebP');};next.src=url;};
if($('videoWallpaper'))$('videoWallpaper').onchange=()=>{const f=$('videoWallpaper').files[0];if(!f)return;if(f.size>80*1024*1024){toast('动态壁纸视频请小于 80 MB');$('videoWallpaper').value='';return;}videoFile=f;if(videoObjectUrl)URL.revokeObjectURL(videoObjectUrl);videoObjectUrl=URL.createObjectURL(f);$('videoPreview').src=videoObjectUrl;$('videoPreviewBox').hidden=false;if($('videoName'))$('videoName').value=f.name.replace(/\.[^.]+$/,'').slice(0,28);$('videoStatus').textContent='已选择：'+f.name+'（'+(f.size/1048576).toFixed(1)+' MB）';};
if($('uploadVideo'))$('uploadVideo').onclick=async()=>{if(!videoFile)return;$('uploadVideo').disabled=true;try{$('videoProgress').hidden=false;$('videoProgress').value=0;$('videoStatus').textContent='正在上传动态壁纸并生成首帧缩略图…';const title=($('videoName')?$('videoName').value.trim():'')||videoFile.name;state=await upload('/upload-video',videoFile,n=>$('videoProgress').value=n,{'X-Marco-Wallpaper-Name':encodeURIComponent(title),'X-Marco-File-Name':encodeURIComponent(videoFile.name)});draw();$('videoStatus').textContent='已保存到「我的壁纸」并设为动态壁纸';$('videoWallpaper').value='';}catch(e){$('videoStatus').textContent=e.message;}finally{$('uploadVideo').disabled=false;$('videoProgress').hidden=true;}};
if($('importVideoUrl'))$('importVideoUrl').onclick=async()=>{const url=$('videoUrl').value.trim();if(!url){toast('请输入视频直链');return;}$('importVideoUrl').disabled=true;$('videoUrlStatus').textContent='电视正在下载视频并生成预览帧，请稍候…';try{const title=$('videoUrlName')?$('videoUrlName').value.trim():'';state=await request('/import-video-url',{revision:state.revision,url,title});draw();$('videoUrlStatus').textContent='已下载保存到「我的壁纸」并设为动态壁纸';$('videoUrl').value='';if($('videoUrlName'))$('videoUrlName').value='';}catch(e){$('videoUrlStatus').textContent=e.message;await refresh().catch(()=>{});}finally{$('importVideoUrl').disabled=false;}};
function crop(){if(!image)return;const c=$('canvas'),ctx=c.getContext('2d'),scale=Math.max(c.width/image.width,c.height/image.height)*Number($('zoom').value)/100,w=image.width*scale,h=image.height*scale,x=(c.width-w)*Number($('cropX').value)/100,y=(c.height-h)*Number($('cropY').value)/100;ctx.fillStyle='#152334';ctx.fillRect(0,0,c.width,c.height);ctx.drawImage(image,x,y,w,h);}
for(const id of ['zoom','cropX','cropY'])$(id).oninput=crop;
function upload(path,file,onProgress,extraHeaders){return new Promise((resolve,reject)=>{const x=new XMLHttpRequest();x.open('POST',path);x.timeout=120000;x.setRequestHeader('Content-Type','application/octet-stream');x.setRequestHeader('X-Marco-Csrf',csrf);x.setRequestHeader('X-Marco-Revision',String(state.revision));if(extraHeaders)for(const [k,v] of Object.entries(extraHeaders))x.setRequestHeader(k,v);x.upload.onprogress=e=>{if(e.lengthComputable)onProgress(Math.round(e.loaded*100/e.total));};x.onerror=x.ontimeout=()=>reject(new Error('上传中断，请检查网络后重试'));x.onload=()=>{let d;try{d=JSON.parse(x.responseText);}catch{d={error:x.responseText};}if(x.status!==200)reject(new Error(d.error||'上传失败'));else resolve(d);};x.send(file);});}
if($('castMedia'))$('castMedia').onchange=async()=>{const f=$('castMedia').files[0];if(!f)return;const isVid=f.type.startsWith('video/')||/\.(mp4|webm|mov|m4v|mkv)$/i.test(f.name);if(f.size>(isVid?128:24)*1024*1024){toast(isVid?'视频请小于 128 MB':'照片请小于 24 MB');$('castMedia').value='';return;}try{$('castProgress').hidden=false;$('castMediaStatus').textContent='正在发送到电视…';state=await upload('/cast',f,n=>$('castProgress').value=n,{'X-Marco-Cast-Kind':isVid?'video':'image','X-Marco-Cast-Name':encodeURIComponent(f.name)});draw();$('castMediaStatus').textContent='已在电视投屏显示：'+f.name;}catch(e){$('castMediaStatus').textContent=e.message;}finally{$('castProgress').hidden=true;$('castMedia').value='';}};
if($('castUrlBtn'))$('castUrlBtn').onclick=async()=>{const u=$('castUrl').value.trim();if(!u){toast('请输入视频或直播链接');return;}if(await act({op:'cast_url',url:u,title:u.split('/').pop().split('?')[0]||'网页视频投屏'}))toast('已发送到电视播放');};
document.querySelectorAll('[data-cast]').forEach(b=>b.onclick=()=>act({op:'cast_control',cmd:b.dataset.cast}));
$('upload').onclick=()=>{if(!image)return;$('upload').disabled=true;$('canvas').toBlob(async blob=>{try{if(!blob)throw new Error('图片处理失败');$('progress').hidden=false;$('uploadStatus').textContent='正在上传…';const title=$('photoName')?$('photoName').value.trim():'';state=await upload('/upload',blob,n=>$('progress').value=n,title?{'X-Marco-Wallpaper-Name':encodeURIComponent(title)}:undefined);draw();$('uploadStatus').textContent='已保存到「我的壁纸」并应用到电视';}catch(e){$('uploadStatus').textContent=e.message;}finally{$('upload').disabled=false;$('progress').hidden=true;}},'image/jpeg',.92);};
async function download(path,name){const response=await fetch(path,{credentials:'same-origin'});if(!response.ok){const d=await response.json();throw new Error(d.error||'下载失败');}saveBlob(await response.blob(),name);}
function saveBlob(blob,name){const url=URL.createObjectURL(blob),a=el('a');a.href=url;a.download=name;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);}
$('backup').onclick=async()=>{$('backup').disabled=true;try{await download('/backup','MarcoTV-backup.zip');toast('备份已交给浏览器保存');}catch(e){toast(e.message);}finally{$('backup').disabled=false;}};
$('restore').onchange=async()=>{const f=$('restore').files[0];if(!f)return;if(f.size>128*1024*1024){toast('备份不得超过 128 MB');return;}try{const d=await upload('/restore',f,n=>$('restoreStatus').textContent='上传 '+n+'%');$('restoreStatus').textContent=d.message;}catch(e){$('restoreStatus').textContent=e.message;}$('restore').value='';};
$('diagnostics').onclick=async()=>{try{const r=await fetch('/diagnostics');if(!r.ok)throw new Error('连接已失效，请重新连接');report=await r.text();$('report').textContent=report;$('report').hidden=false;$('downloadReport').hidden=false;}catch(e){toast(e.message);}};
$('downloadReport').onclick=()=>saveBlob(new Blob([report],{type:'text/plain;charset=utf-8'}),'MarcoTV-diagnostic.txt');
$('disconnect').onclick=async()=>{try{await act({op:'cancel'});await request('/action',{op:'disconnect'});}catch{}state=null;draft={};$('connected').hidden=true;$('tabs').hidden=true;$('pair').hidden=false;$('connection').textContent='连接已关闭，请在电视重新开启';};
document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>selectTab(b.dataset.tab));
const seed=location.hash.slice(1);if(/^[a-f0-9]{32}$/.test(seed))$('code').value=seed;history.replaceState(null,'',location.pathname);
let nonce;try{const bytes=new Uint8Array(16);crypto.getRandomValues(bytes);nonce=Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');}catch{$('pairStatus').textContent='浏览器不支持安全连接，请换用系统浏览器';}
$('connect').onclick=async()=>{if(!nonce)return;clearTimeout(pairTimer);$('connect').disabled=true;async function poll(){try{const d=await request('/pair',{code:$('code').value.trim(),nonce});if(d.status==='connected'){csrf=d.csrf;await refresh();$('connect').disabled=false;}else{$('pairStatus').textContent='请在电视选择“允许连接”…';pairTimer=setTimeout(poll,1800);}}catch(e){$('pairStatus').textContent=e.message;$('connect').disabled=false;}}poll();};
refresh().catch(()=>{$('connection').textContent='等待连接 · 仅限家庭局域网';if(seed)$('connect').click();});
setInterval(()=>{if(state&&!document.hidden&&!$('sheet').open&&!drag&&!pending&&!Object.keys(draft).length)refresh().catch(()=>{});},5000);
