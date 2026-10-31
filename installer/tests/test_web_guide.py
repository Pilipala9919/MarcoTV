# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Exercise the shipped route script with a minimal DOM; no browser or TV mutation."""
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HARNESS = r'''
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const elements=new Map();
function element(id){if(!elements.has(id))elements.set(id,{value:'',hidden:false,textContent:'',children:[],addEventListener(){},append(...x){this.children.push(...x)},replaceChildren(...x){this.children=x},focus(){},scrollIntoView(){}});return elements.get(id)}
const document={getElementById:element,querySelector:element,querySelectorAll(){return []},createElement(){return {textContent:'',append(){}}}};
const c=vm.createContext({document,location:{hash:'#home'},matchMedia(){return {matches:true}}});
vm.runInContext(fs.readFileSync('docs/install/app.js','utf8'),c);
assert.equal(element('purpose').value,'home');
assert.equal(element('device').hidden,true);
assert.equal(element('entry').hidden,true);
assert.equal(element('goalTitle').textContent,'让 Home 回到 Marco TV');
let combinations=0;
for(const sys of ['android','unknown','other'])for(const dev of ['windows','android','iphone','other'])for(const entry of ['unknown','browser','receive','adb','pair','none']){
 const r=c.route(sys,dev,entry);assert(r.title&&r.intro&&r.steps.length&&r.limit);
 if(sys!=='android'||entry==='pair'||entry==='none')assert(!r.download);
 if(dev==='iphone')assert(r.download!=='android'&&r.download!=='windows');
 combinations++;
}
for(const sys of ['unknown','other'])assert(!c.homeRoute(sys).download);
const home=c.homeRoute('android');assert(home.docs&&!home.download&&home.steps.length===4);
assert(home.limit.includes('尚未正式发布')&&home.limit.includes('不自动授权'));
element('system').value='android';c.render();
assert.equal(element('routeTitle').textContent,home.title);
assert.equal(element('steps').children.length,4);
element('purpose').value='install';c.selectPurpose();assert.equal(element('device').hidden,false);
element('device').value='iphone';element('entry').value='browser';c.render();
const link=element('download').children[0];
const stable=JSON.parse(fs.readFileSync('docs/updates/stable.json','utf8'));
assert.equal(link.href,'https://marcotv.wlj9919.top/MarcoTV-'+stable.versionName+'.apk');
const html=fs.readFileSync('docs/install/index.html','utf8');
for(const id of ['purpose','system','device','entry','goalTitle','routeTitle','steps','download'])assert(html.includes('id="'+id+'"'));
console.log('OK '+combinations+' installation route combinations; home routing, hash entry, form mode, render and release link checks passed');
'''

subprocess.run(['node', '-e', HARNESS], cwd=ROOT, check=True)
