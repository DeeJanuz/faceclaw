const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),ts=require('typescript');
test('app menu preserves the visible app and cannot select unseen rows before its first frame',()=>{
 const module={exports:{}};let fills=0,blits=0,inputs=0;
 class Image{constructor(w,h){this.width=w;this.height=h;this.pixels=new Uint8Array(w*h);}fillRect(){fills++;}bitBlt(){blits++;}}
 const dependencies={'../../graphics/image':{GrayImage:Image},'./geometry':{appViewportRect:()=>({x:0,y:0,width:288,height:288})}};
 vm.runInNewContext(ts.transpileModule(fs.readFileSync('app/ui/shell/extension-layer.ts','utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.CommonJS}}).outputText,{module,exports:module.exports,require:name=>dependencies[name]});
 const layer=new module.exports.ExtensionLayer(()=>inputs++,()=>{},()=>{},'min',true,false,false,true);
 const underlay=new Image(288,288);
 assert.equal(layer.dimUnderneath,false);assert.equal(layer.paint({},()=>underlay),underlay);assert.equal(fills,0);
 layer.handleInput({type:'long-press-release'});layer.handleInput({type:'click'});assert.equal(inputs,0);
 let dismissed=0;layer.handleInput({type:'double-click'},{stack:{pop:()=>dismissed++}});assert.equal(dismissed,1);
 layer.setFrame(new Uint8Array(288*288),288,288);assert.equal(layer.dimUnderneath,0);
 layer.paint({},()=>underlay);assert.equal(fills,1);assert.equal(blits,1);
 layer.handleInput({type:'click'});assert.equal(inputs,1);
});
