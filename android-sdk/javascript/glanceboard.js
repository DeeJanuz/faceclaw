'use strict';
const fail=()=>{throw new TypeError('Invalid Glanceboard payload');};
const integer=(v,min,max)=>Number.isSafeInteger(v)&&v>=min&&v<=max?v:fail();
const text=(v,max)=>typeof v==='string'&&v.length<=max?v.replace(/[\u0000-\u001f\u007f-\u009f]/g,' '):fail();
const token=v=>typeof v==='string'&&/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/.test(v)?v:fail();
const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function encodeBytes(bytes){let out='';for(let i=0;i<bytes.length;i+=3){const n=(bytes[i]<<16)|((bytes[i+1]||0)<<8)|(bytes[i+2]||0);out+=alphabet[(n>>>18)&63]+alphabet[(n>>>12)&63]+(i+1<bytes.length?alphabet[(n>>>6)&63]:'=')+(i+2<bytes.length?alphabet[n&63]:'=');}return out;}
function decodeBits(value,length){
 if(typeof value!=='string'||value.length>16384||value.length%4||!/^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value))fail();
 const bytes=[];let held=0,bits=0;for(const char of value){if(char==='=')break;bits=(bits<<6)|alphabet.indexOf(char);held+=6;if(held>=8){held-=8;bytes.push((bits>>>held)&255);}}
 if(bytes.length!==length||encodeBytes(bytes)!==value)fail();return bytes;
}
function validateWidgetRegistry(value){
 if(!value||JSON.stringify(value).length>8192||value.version!==1||!Array.isArray(value.widgets)||value.widgets.length>8)fail();
 const ids=new Set();return {version:1,widgets:value.widgets.map(row=>{
  const id=token(row.id),label=text(row.label,80),kind=row.kind;
  if(ids.has(id)||!label.trim()||!['list','scene'].includes(kind))fail();ids.add(id);
  const uses=row.uses===undefined?[]:row.uses;
  if(!Array.isArray(uses)||uses.length>3||new Set(uses).size!==uses.length||uses.some(v=>!['battery','weather','time-format'].includes(v)))fail();
  return {id,label,kind,rows:integer(row.rows,1,2),refreshMs:integer(row.refreshMs,5000,60000),uses:[...uses]};
 })};
}
function validateWidgetContent(value,registry,now=Date.now()){
 const catalog=validateWidgetRegistry(registry);
 if(!value||JSON.stringify(value).length>32768||value.version!==2)fail();
 const widgetId=token(value.widgetId),widget=catalog.widgets.find(row=>row.id===widgetId);if(!widget)fail();
 const expiresAt=integer(value.expiresAt,now+1,now+60000),base={version:2,widgetId,expiresAt};
 if(widget.kind==='list'){
  if(JSON.stringify(value).length>16384||!Array.isArray(value.entries)||value.entries.length>12)fail();
  const ids=new Set(),entries=[];
  for(const row of value.entries){const id=text(row.id,256);if(!id||ids.has(id))fail();ids.add(id);
   const expiry=row.expiresAt===undefined?expiresAt:integer(row.expiresAt,1,Number.MAX_SAFE_INTEGER);
   if(expiry<=now)continue;
   entries.push({id,title:text(row.title,160),detail:text(row.detail,256),expiresAt:Math.min(expiry,expiresAt)});
  }
  return {...base,title:text(value.title,80),emptyText:text(value.emptyText,160),entries};
 }
 if(!Array.isArray(value.commands)||value.commands.length>96)fail();let pixels=0;
 return {...base,commands:value.commands.map(command=>{
  const x=integer(command.x,0,287),y=integer(command.y,0,widget.rows*144-1),value=integer(command.value,0,255),op=command.op;
  const out={op,x,y,value};
  if(op==='text')return {...out,text:text(command.text,256),width:integer(command.width,1,288-x)};
  if(!['rect','bitmap'].includes(op))fail();
  const width=integer(command.width,1,288-x),height=integer(command.height,1,widget.rows*144-y);
  if(op==='rect')return {...out,width,height};
  pixels+=width*height;if(pixels>288*widget.rows*144)fail();decodeBits(command.bits,Math.ceil(width*height/8));
  return {...out,width,height,bits:command.bits};
 })};
}
/** Passive scene builder. Bitmap pixels are packed MSB-first; zero bits are transparent. */
class GlanceCanvas{
 constructor(widgetId,expiresAt=Date.now()+60000){this.widgetId=widgetId;this.expiresAt=expiresAt;this.commands=[];}
 text(x,y,text,width=288-x,value=190){this.commands.push({op:'text',x,y,text,width,value});return this;}
 rect(x,y,width,height,value=255){this.commands.push({op:'rect',x,y,width,height,value});return this;}
 bitmap(x,y,width,height,grayPixels,value=255){integer(width,1,288);integer(height,1,288);if(grayPixels.length!==width*height)fail();const bytes=new Uint8Array(Math.ceil(width*height/8));for(let i=0;i<grayPixels.length;i++)if(grayPixels[i]>0)bytes[i>>3]|=1<<(7-(i&7));this.commands.push({op:'bitmap',x,y,width,height,value,bits:encodeBytes(bytes)});return this;}
 build(){return {version:2,widgetId:this.widgetId,expiresAt:this.expiresAt,commands:this.commands.map(row=>({...row}))};}
}
module.exports={validateWidgetRegistry,validateWidgetContent,GlanceCanvas,decodeBits};
