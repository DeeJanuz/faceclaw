const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),ts=require('typescript');
const {NotificationLeases}=require('../.test-build/app/apps/external/extension-policy.js');
function harness(registry = {}) {
 let catalog=[{packageName:'app.native',name:'Native'}];
 let allowed=true,locked=false,screenOn=true,foreground='apk:owner',seq=0;
 const module={exports:{}},sent=[],dismissed=[],opened=[];
 let sources=[{key:'native-secret',postTime:5,packageName:'app.native',appName:'Native',title:'Native note',actions:[]},{key:'apk:secret',postTime:6,component:'private/component',target:'private-target',replyToken:'SECRET',packageName:'app.external',appName:'External',title:'External note',actions:[{index:0,title:'Open',enabled:true}]}];
 const imports={
 '../../assistant/tool-registry':registry,
 '../../ui/shell/shell':{shell:{isScreenOn:()=>screenOn,foregroundWindow:()=>({windowId:foreground}),getWindows:()=>[{windowId:'prior-app'}],focusWindow:id=>opened.push(`focus:${id}`),sleepAtAppRoot:()=>opened.push('sleep'),openNotificationModal:key=>opened.push(key),getBatteryLevels:()=>({})}},
 '../../native/notification-icons':{readActiveNotifications:()=>sources,dismissNotification:(key)=>{dismissed.push(key);return key!=='apk:secret';}},
 '../../native/external-notifications':{invokeExternalNotification:key=>{opened.push(key);return true;}},
 '../../native/notification-apps':{readNotificationApps:()=>catalog},
 './extension-policy':require('../.test-build/app/apps/external/extension-policy.js'),
 './app-capabilities':{AppCapabilityRegistry:class{constructor(){}tools(){return [];}event(){return false;}remove(){}has(){return false;}}},
 };
 vm.runInNewContext(ts.transpileModule(fs.readFileSync('app/apps/external/extension-platform.ts','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,{module,exports:module.exports,require:name=>imports[name]||{},java:{util:{UUID:{randomUUID:()=>({toString:()=>`id-${++seq}`})}}}});
 const platform=Object.create(module.exports.ExtensionPlatform.prototype);
 Object.assign(platform,{generation:3,features:[],ownNotifications:new Map(),conversationIds:new Map(),notificationRevision:0,notificationAppsAt:0,notificationApps:[],isLocked:()=>locked,isProtected:()=>false,native:{isExtensionGranted:()=>allowed,send:(owner,type,json)=>sent.push({owner,type,data:JSON.parse(json)}),sendExtension:(owner,feature,type,json)=>{sent.push({owner,feature,type,data:JSON.parse(json)});return true;}}});
 return {catalog:v=>{catalog=v;platform.notificationAppsAt=0;},platform,sent,dismissed,opened,allowed:v=>allowed=v,locked:v=>locked=v,screen:v=>screenOn=v,foreground:v=>foreground=v,sources:v=>sources=v,snapshot:()=>JSON.parse(sent.map(x=>x.data.json).join(''))};
}
test('own inbox needs explicit content grant and sanitizes foreign capabilities',()=>{
 for(const deny of [h=>h.allowed(false),h=>h.locked(true),h=>h.screen(false)]) {const h=harness();deny(h);h.platform.publishOwnNotifications('owner');assert.equal(h.sent.length,0);}
 const h=harness();h.platform.publishOwnNotifications('owner');const snapshot=h.snapshot();
 assert.equal(snapshot.notifications.length,2);assert.equal(snapshot.generation,3);assert.equal(snapshot.notificationApps[0].name,'Native');
 const serialized=JSON.stringify(snapshot);for(const secret of ['native-secret','apk:secret','private-target','private/component','SECRET']) assert.equal(serialized.includes(secret),false);
 assert.deepEqual(snapshot.notifications[1].actions,[]);
});
test('own leases reject cross-owner, stale versions, revocation and unsupported reply',()=>{
 const h=harness();h.platform.publishOwnNotifications('owner');const entry=h.snapshot().notifications[0],request={action:'open',key:entry.key,postTime:entry.postTime};
 for(const data of [{...request,action:'reply'},{...request,postTime:4}]) assert.equal(h.platform.actOnOwnNotification('owner',data).ok,false);
 assert.equal(h.platform.actOnOwnNotification('other',request).ok,false);assert.equal(h.opened.length,0);
 assert.equal(h.platform.actOnOwnNotification('owner',request).ok,true);assert.equal(h.opened[0],'native-secret');
 h.allowed(false);assert.equal(h.platform.actOnOwnNotification('owner',request).ok,false);
 h.allowed(true);h.platform.clearOwnNotifications('owner');assert.equal(h.platform.actOnOwnNotification('owner',request).ok,false);
});
test('group dismiss validates every lease before mutation and returns partial outcomes without retry',()=>{
 const h=harness();h.platform.publishOwnNotifications('owner');const items=h.snapshot().notifications.map(({key,postTime})=>({key,postTime}));
 assert.equal(h.platform.actOnOwnNotification('owner',{action:'dismiss-group',items:[items[0],{...items[1],postTime:0}]}).ok,false);assert.equal(h.dismissed.length,0);
 assert.equal(h.platform.actOnOwnNotification('owner',{action:'dismiss-group',items:[items[0],items[0]]}).ok,false);assert.equal(h.dismissed.length,0);
 const result=h.platform.actOnOwnNotification('owner',{action:'dismiss-group',items});assert.equal(result.ok,false);assert.deepEqual(Array.from(result.outcomes,x=>x.ok),[true,false]);assert.deepEqual(h.dismissed,['native-secret','apk:secret']);
});
test('launcher uninstall requires fresh winning-surface input and current uninstallable catalog entry',async()=>{
 const h=harness();let uninstalled=[];h.platform.hooks={apps:()=>[{appId:'builtin'},{appId:'evenhub:example',uninstallable:true}],uninstallApp:id=>uninstalled.push(id)};h.platform.controls=(_owner,feature)=>feature==='ui.launcher';h.platform.lastGesture=new Map();h.platform.native.sendExtension=()=>true;
 await h.platform.action('owner','ui.launcher',3,'uninstall-app',{callId:'call',appId:'evenhub:example'});assert.equal(uninstalled.length,0);
 h.platform.lastGesture.set('ui.launcher',Date.now());await h.platform.action('owner','ui.launcher',3,'uninstall-app',{callId:'call',appId:'builtin'});assert.equal(uninstalled.length,0);
 h.platform.lastGesture.set('ui.launcher',Date.now());await h.platform.action('owner','ui.launcher',3,'uninstall-app',{callId:'call',appId:'evenhub:example'});assert.deepEqual(uninstalled,['evenhub:example']);
 await h.platform.action('owner','ui.launcher',3,'uninstall-app',{callId:'call',appId:'evenhub:example'});assert.equal(uninstalled.length,1);
});

test('notification visit return uses the host-captured origin instead of launcher authority', async () => {
 const h=harness(); h.platform.controls=()=>true; h.platform.lastGesture=new Map(); h.platform.lastTapGesture=new Map();
 h.platform.notificationVisit={presentationId:'lease-1',component:'owner',generation:3,originWindowId:'prior-app',wokeScreen:false,handedOff:true};
 await h.platform.action('owner','ui.notifications',3,'notification-return',{callId:'return',presentationId:'lease-1'});
 assert.deepEqual(h.opened,['focus:prior-app']);
 assert.equal(h.platform.notificationVisit,null);
 assert.equal(h.platform.lastGesture.has('ui.notifications'),false);
});

test('stale notification visit return cannot focus a client-supplied window', async () => {
 const h=harness(); h.platform.controls=()=>true; h.platform.lastTapGesture=new Map([['ui.notifications',Date.now()]]); h.platform.lastGesture=new Map([['ui.notifications',Date.now()]]);
 h.platform.notificationVisit={presentationId:'lease-1',component:'owner',generation:3,originWindowId:'prior-app',wokeScreen:false,handedOff:true};
 await h.platform.action('owner','ui.notifications',3,'notification-return',{callId:'return',presentationId:'wrong',windowId:'attacker'});
 assert.deepEqual(h.opened,[]);
 assert.notEqual(h.platform.notificationVisit,null);
});

test('host surface cleanup preserves the source lease across a successful handoff', () => {
 const h=harness();
 h.platform.lastGesture=new Map(); h.platform.lastTapGesture=new Map();
 h.platform.notificationPresentation={id:'lease-1',key:'native-secret',postTime:5};
 h.platform.notificationVisit={presentationId:'lease-1',component:'owner',generation:3,originWindowId:'prior-app',wokeScreen:false,handedOff:true,reopenCount:0};
 h.platform.closeSurface('ui.notifications','lease-1');
 assert.deepEqual(h.platform.notificationPresentation,{id:'lease-1',key:'native-secret',postTime:5});
 assert.equal(h.platform.notificationVisit.handedOff,true);
});

test('a reviewed visit return is authorized for a bounded automatic reply close', async () => {
 const h=harness(); h.platform.controls=()=>true; h.platform.lastGesture=new Map(); h.platform.lastTapGesture=new Map();
 h.platform.isProtected=()=>true;
 h.platform.notificationVisit={presentationId:'lease-1',component:'owner',generation:3,originWindowId:'prior-app',wokeScreen:false,handedOff:true,reviewed:true,reviewedAt:Date.now(),reopenCount:0};
 await h.platform.action('owner','ui.notifications',3,'notification-return',{callId:'return',presentationId:'lease-1'});
 assert.deepEqual(h.opened,['focus:prior-app']);
});

test('an expired reviewed visit cannot bypass a protected flow', async () => {
 const h=harness(); h.platform.controls=()=>true; h.platform.lastGesture=new Map(); h.platform.lastTapGesture=new Map(); h.platform.isProtected=()=>true;
 h.platform.notificationVisit={presentationId:'lease-1',component:'owner',generation:3,originWindowId:'prior-app',wokeScreen:false,handedOff:true,reviewed:true,reviewedAt:Date.now()-5001,reopenCount:0};
 await h.platform.action('owner','ui.notifications',3,'notification-return',{callId:'return',presentationId:'lease-1'});
 assert.deepEqual(h.opened,[]);
 assert.notEqual(h.platform.notificationVisit,null);
});

test('abandoning a failed wake-origin launch restores its captured origin and sleep', async () => {
 const h=harness(); h.platform.controls=()=>true; h.platform.lastGesture=new Map(); h.platform.lastTapGesture=new Map();
 h.platform.notificationPresentation={id:'lease-1',key:'native-secret',postTime:5};
 h.platform.notificationVisit={presentationId:'lease-1',component:'owner',generation:3,originWindowId:'prior-app',wokeScreen:true,handedOff:true,handedOffAt:Date.now(),reopenCount:0};
 await h.platform.action('owner','ui.notifications',3,'notification-abandon',{callId:'abandon',presentationId:'lease-1'});
 assert.deepEqual(h.opened,['focus:prior-app','sleep']);
 assert.equal(h.platform.notificationVisit,null);
 assert.equal(h.platform.notificationPresentation,null);
});

test('a stale failed-launch cleanup cannot focus or sleep the host', async () => {
 const h=harness(); h.platform.controls=()=>true; h.platform.lastGesture=new Map(); h.platform.lastTapGesture=new Map();
 h.platform.notificationVisit={presentationId:'lease-1',component:'owner',generation:3,originWindowId:'prior-app',wokeScreen:true,handedOff:true,handedOffAt:Date.now()-15001,reopenCount:0};
 await h.platform.action('owner','ui.notifications',3,'notification-abandon',{callId:'abandon',presentationId:'lease-1'});
 assert.deepEqual(h.opened,[]);
 assert.notEqual(h.platform.notificationVisit,null);
});

test('pointer bounds and native visible-viewport acceptance gate fresh action authority',()=>{
 const h=harness();h.platform.feature=()=>({component:'owner'});h.platform.lastGesture=new Map();let sent=0,accept=true;h.platform.native.sendExtensionPointer=()=>{sent++;return accept;};
 for(const [x,y,w,height] of [[-1,0,32,16],[32,0,32,16],[0,16,32,16],[0.5,0,32,16],[NaN,0,32,16],[0,0,641,16]]) assert.equal(h.platform.surfacePointer('ui.launcher',x,y,w,height),false);
 assert.equal(sent,0);assert.equal(h.platform.lastGesture.size,0);accept=false;assert.equal(h.platform.surfacePointer('ui.launcher',1,1,32,16),false);assert.equal(h.platform.lastGesture.size,0);
 accept=true;assert.equal(h.platform.surfacePointer('ui.launcher',1,1,32,16),true);assert.ok(h.platform.lastGesture.get('ui.launcher')>0);
});
test('explicit device-tool grant preserves ordinary proactive and current availability gates',async()=>{
 const module={exports:{}};vm.runInNewContext(ts.transpileModule(fs.readFileSync('app/assistant/tool-registry.ts','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,{module,exports:module.exports,setTimeout,clearTimeout});const registry=module.exports;let calls=0,available=true,controls=true;
 registry.toolRegistry.register({spec:{name:'apps.launch',description:'Synthetic launch',inputSchema:{},availability:'always',proactive:false},isAvailable:()=>available,handler:()=>{calls++;return {ok:true,content:'Launched'};}});
 const h=harness(registry);h.platform.controls=()=>controls;
 assert.equal((await registry.toolRegistry.callTool('apps.launch',{}, {proactive:true})).ok,false);assert.equal(calls,0);
 assert.equal((await h.platform.tool('owner',3,'apps.launch',{})).ok,true);assert.equal(calls,1);
 controls=false;assert.equal((await h.platform.tool('owner',3,'apps.launch',{})).ok,false);controls=true;available=false;assert.equal((await h.platform.tool('owner',3,'apps.launch',{})).ok,false);
 assert.equal((await registry.toolRegistry.callTool('apps.launch',{}, {proactive:true})).ok,false);assert.equal(calls,1);
});

test('generic notification tools redact content and cannot dispatch an unreviewed reply',async()=>{
 const h=harness();h.platform.controls=()=>true;h.platform.toolNotifications=new NotificationLeases(()=> 'opaque-reply');
 h.sources([{key:'native',postTime:5,appName:'Messages',title:'Secret sender',text:'Secret message',actions:[{index:0,title:'Reply',enabled:true,acceptsText:true}]}]);
 const listed=await h.platform.tool('owner',3,'notifications.list',{});assert.equal(listed.ok,true);
 assert.equal(listed.content.includes('Secret'),false);assert.equal(listed.content.includes('native'),false);
 const entry=JSON.parse(listed.content)[0];
 assert.equal((await h.platform.tool('owner',3,'host.notifications.reply',{key:entry.key,postTime:5,actionIndex:0,text:'Hi'})).ok,false);
 let reviews=0;h.platform.messaging={reviewNotification:async()=>{reviews++;return {status:'pending'};}};
 const scope={owner:'owner',project:'project',identity:'identity',session:'session'};
 const result=await h.platform.tool('owner',3,'host.notifications.reply',{key:entry.key,postTime:5,actionIndex:0,text:'Hi'},scope);
 assert.equal(reviews,1);assert.equal(JSON.parse(result.content).status,'pending');
 assert.equal((await h.platform.tool('owner',3,'host.notifications.action',{key:entry.key,postTime:5,actionIndex:0},scope)).ok,false);
});

test('only host input delivered to the foreground notification owner authorizes its reader actions',()=>{
 const h=harness();h.platform.controls=(owner,feature)=>owner==='owner'&&feature==='ui.notifications';h.platform.lastGesture=new Map();
 for(const deny of [()=>h.foreground('apk:other'),()=>h.locked(true),()=>h.screen(false)]) {
  deny();h.platform.windowInput('owner',{type:'click'});assert.equal(h.platform.lastGesture.size,0);h.foreground('apk:owner');h.locked(false);h.screen(true);
 }
 h.platform.windowInput('other',{type:'click'});h.platform.windowInput('owner',{type:'scroll-down'});assert.equal(h.platform.lastGesture.size,0);
 h.platform.windowInput('owner',{type:'click'});assert.ok(h.platform.lastGesture.get('ui.notifications')>0);
});

test('large app catalogs retain Signal and publish catalog-only changes in bounded fragments',()=>{
 const h=harness();h.platform.hooks={};h.platform.controls=()=>true;
 const apps=Array.from({length:900},(_,i)=>({packageName:`app.${i}`,name:`Application ${i}`}));
 apps.push({packageName:'org.thoughtcrime.securesms',name:'Signal'});h.catalog(apps);
 h.platform.publishOwnNotifications('owner');
 assert.equal(h.snapshot().notificationApps.length,901);
 assert.equal(h.snapshot().notificationApps.at(-1).name,'Signal');
 assert.ok(h.sent.length>1);assert.ok(h.sent.every(x=>x.data.json.length<=16000&&x.data.total<=128));
 assert.equal(h.platform.ownHostState('owner').notificationApps,undefined);
 h.sent.length=0;h.catalog([...apps,{packageName:'app.new',name:'Z new'}]);
 h.platform.publishOwnNotifications('owner');assert.equal(h.snapshot().notificationApps.length,902);
});

test('locked startup and rejected delivery do not suppress the launcher catalog after unlock',()=>{
 const h=harness();let accepted=false;
 h.platform.hooks={apps:()=>[{appId:'clock',title:'Clock'}]};h.platform.feature=feature=>feature==='ui.launcher'?{component:'owner'}:undefined;
 h.platform.native.sendExtension=(_owner,_feature,_type,json)=>{h.sent.push(JSON.parse(json));return accepted;};
 h.locked(true);h.platform.publishState();assert.equal(h.sent.length,0);
 h.locked(false);h.platform.publishState();assert.equal(h.sent.length,1);assert.equal(h.sent[0].apps[0].appId,'clock');
 accepted=true;h.platform.publishState();assert.equal(h.sent.length,2);
 h.platform.publishState();assert.equal(h.sent.length,2);
 h.platform.native.openExtensionSurface=()=>true;assert.equal(h.platform.openSurface('ui.launcher',576,260),true);
 assert.equal(h.sent.length,3,'reopening resends the current catalog');
});
test('notification catalog retries a rejected fragment and resends after recipient disconnects',()=>{
 const h=harness();let accepted=false;
 Object.assign(h.platform,{reviews:new Map(),uiNotifications:new NotificationLeases(()=>`lease`),lastNotificationSnapshot:'',isProtected:()=>false,appCapabilities:{remove(){}}});
 h.sources([]);h.catalog(Array.from({length:901},(_,i)=>({packageName:`app.${i}`,name:`Application ${i}`})));
 h.platform.feature=feature=>feature==='ui.notifications'?{component:'owner',generation:3}:undefined;
 h.platform.native.sendExtension=(_owner,_feature,_type,json)=>{h.sent.push(JSON.parse(json));return accepted;};
 h.platform.notificationsChanged();assert.equal(h.platform.lastNotificationSnapshot,'');
 accepted=true;h.sent.length=0;h.platform.notificationsChanged();assert.ok(h.sent.length>1);
 assert.equal(JSON.parse(h.sent.map(x=>x.json).join('')).notificationApps.length,901);
 const count=h.sent.length;h.platform.notificationsChanged();assert.equal(h.sent.length,count);
 h.platform.onNativeEvent('owner','disconnected',{});h.sent.length=0;h.platform.notificationsChanged();
 assert.equal(JSON.parse(h.sent.map(x=>x.json).join('')).notificationApps.length,901);
});
