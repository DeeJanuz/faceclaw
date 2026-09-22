#!/usr/bin/env node
'use strict';
const fs=require('node:fs'),path=require('node:path');
const {validateWidgetRegistry,validateWidgetContent,decodeBits}=require('./glanceboard');
const usage='Usage: faceclaw-widget init DIRECTORY | validate REGISTRY.json [CONTENT.json] [--now EPOCH_MS] | preview REGISTRY.json CONTENT.json --out PREVIEW.html [--now EPOCH_MS]';
function read(file){return JSON.parse(fs.readFileSync(file,'utf8'));}
function escape(value){return String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function preview(content,widget){
 const gray=value=>`rgb(${value},${value},${value})`;let nodes='';
 if(widget.kind==='list'){
  nodes+=`<text x="8" y="24" fill="white">${escape(content.title)}</text>`;
  const rows=content.entries.length?content.entries.map(row=>`${row.title} · ${row.detail}`):[content.emptyText];
  rows.forEach((row,i)=>nodes+=`<text x="8" y="${50+i*24}" fill="#bbb">${escape(row)}</text>`);
 }else for(const command of content.commands){
  if(command.op==='text')nodes+=`<svg x="${command.x}" y="${command.y}" width="${command.width}" height="24"><text y="18" fill="${gray(command.value)}">${escape(command.text)}</text></svg>`;
  if(command.op==='rect')nodes+=`<rect x="${command.x}" y="${command.y}" width="${command.width}" height="${command.height}" fill="${gray(command.value)}"/>`;
  if(command.op==='bitmap'){
   const bits=decodeBits(command.bits,Math.ceil(command.width*command.height/8));let d='';
   for(let i=0;i<command.width*command.height;i++)if(bits[i>>3]&(1<<(7-(i&7))))d+=`M${command.x+i%command.width},${command.y+Math.floor(i/command.width)}h1v1h-1z`;
   nodes+=`<path d="${d}" fill="${gray(command.value)}"/>`;
  }
 }
 return `<!doctype html><meta charset="utf-8"><title>${escape(widget.label)}</title><style>body{background:#222;color:white;font:17px sans-serif}svg{background:black;max-width:100%;font:17px sans-serif}</style><h1>${escape(widget.label)}</h1><p>SDK preview, ${widget.rows} slot(s). Font rendering is approximate; validate on your target device.</p><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 288 ${widget.rows*144}" width="576" height="${widget.rows*288}">${nodes}</svg>`;
}
function main(args){
 const command=args.shift();if(command==='init'){
  const directory=args[0];if(!directory)throw Error(usage);fs.mkdirSync(directory,{recursive:true});
  const registry={version:1,widgets:[{id:'default',label:'My widget',kind:'list',rows:1,refreshMs:30000}]};
  const content={version:2,widgetId:'default',title:'My widget',emptyText:'Caught up',expiresAt:Date.now()+60000,entries:[{id:'example',title:'Hello',detail:'Published by my app'}]};
  fs.writeFileSync(path.join(directory,'widgets.json'),JSON.stringify(registry,null,2)+'\n',{flag:'wx'});
  fs.writeFileSync(path.join(directory,'content.json'),JSON.stringify(content,null,2)+'\n',{flag:'wx'});
  fs.writeFileSync(path.join(directory,'widget.js'),`const {appControls}=require('@faceclaw/motion');\nconst registry=require('./widgets.json');\nexports.onHostEvent=(service,type,data)=>{\n const sdk=appControls(service);\n if(type==='connected'||type==='capabilities')sdk.registerGlanceboardWidgets(registry);\n if(type==='glanceboard-request'&&data.widgetId==='default')sdk.publishGlanceboardWidget({...require('./content.json'),expiresAt:Date.now()+60000});\n};\n`,{flag:'wx'});
  console.log('Created widget declarations, sample content, and service event handler.');return;
 }
 const nowAt=args.indexOf('--now'),now=nowAt<0?Date.now():Number(args[nowAt+1]);if(!Number.isSafeInteger(now)||now<0)throw Error('Invalid --now');if(nowAt>=0)args.splice(nowAt,2);
 const outAt=args.indexOf('--out'),out=outAt<0?undefined:args[outAt+1];if(outAt>=0)args.splice(outAt,2);
 if(!['validate','preview'].includes(command)||!args[0])throw Error(usage);
 const registry=validateWidgetRegistry(read(args[0]));
 const content=args[1]?validateWidgetContent(read(args[1]),registry,now):null;
 if(command==='preview'){if(!content||!out)throw Error(usage);fs.writeFileSync(out,preview(content,registry.widgets.find(widget=>widget.id===content.widgetId)));console.log('Wrote offline widget preview.');}
 else console.log(`Valid registry: ${registry.widgets.length} widget(s)${content?'; content valid':''}.`);
}
try{main(process.argv.slice(2));}catch(error){console.error(error instanceof TypeError?'Invalid Glanceboard payload.':error.message);process.exitCode=1;}
