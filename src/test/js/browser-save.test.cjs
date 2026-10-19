const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync('src/main/java/com/wilddeck/app/BrowserGameServer.java','utf8');
const script=source.match(/private static final String SCRIPT="""\n([\s\S]*?)\n """;/)[1];
const tick=()=>new Promise(resolve=>setImmediate(resolve));
async function page(boot,{saved,respond,storageError=false}={}){
 let stored=saved,handler,reloads=0;
 const calls=[],input={disabled:false,value:'pass'},button={disabled:false};
 const status={textContent:'',append(){}};
 const form={addEventListener(event,callback){handler=callback;}};
 const context={
  boot,JSON,Error,confirm:()=>true,
  document:{getElementById:()=>status,querySelectorAll:q=>q==='form'?[form]:[input,button],createElement:()=>({})},
  localStorage:{
   getItem(){return stored??null;},
   setItem(key,value){if(storageError)throw Error('Storage full');stored=value;},
   removeItem(){stored=undefined;}
  },
  location:{reload(){reloads++;}},
  FormData:class{get(){return input.disabled?null:input.value;}},
  async fetch(path,options){
   calls.push({path,data:JSON.parse(options.body)});
   const result=respond?await respond(path,JSON.parse(options.body)):{status:200,state:{revision:2,fresh:false,save:'wd1.next'}};
   return {ok:result.status===200,status:result.status,json:async()=>result.state,text:async()=>result.error??'Invalid save'};
  }
 };
 vm.runInNewContext(script,context);await tick();
 return {input,status,calls,get stored(){return stored;},get reloads(){return reloads;},
  async submit(){await handler({preventDefault(){}});await tick();}};
}
test('submission reads enabled controls and saves returned token before reload',async()=>{
 const p=await page({fresh:false,revision:1,save:'wd1.old'});
 assert.equal(p.input.disabled,false);await p.submit();
 assert.equal(p.calls[0].data.command,'pass');assert.equal(p.calls[0].data.revision,1);
 assert.equal(JSON.parse(p.stored).save,'wd1.next');assert.equal(p.reloads,1);
});
test('fresh process restores saved token before enabling commands',async()=>{
 const saved=JSON.stringify({revision:8,save:'wd1.previous'});
 const p=await page({fresh:true,revision:0,save:''},{saved,respond:async()=>({status:200,state:{revision:8,save:'wd1.restored'}})});
 assert.equal(p.calls[0].path,'/restore');assert.equal(p.calls[0].data.save,'wd1.previous');
 assert.equal(p.input.disabled,true);assert.equal(p.reloads,1);assert.equal(JSON.parse(p.stored).save,'wd1.restored');
});
test('bad restore keeps original save and disables commands',async()=>{
 const saved=JSON.stringify({revision:8,save:'broken'});
 const p=await page({fresh:true,revision:0,save:''},{saved,respond:async()=>({status:400})});
 assert.equal(p.stored,saved);assert.equal(p.input.disabled,true);assert.match(p.status.textContent,/Save\/restore stopped/);
 assert.equal(p.reloads,0);
});
test('quota failure preserves previous save and reports unsaved order',async()=>{
 const saved=JSON.stringify({revision:1,save:'wd1.old'});
 // Let initial refresh save, then reject the action's new token.
 const p=await page({fresh:false,revision:1,save:'wd1.old'},{saved,storageError:true});
 assert.equal(p.stored,saved);assert.equal(p.input.disabled,true);assert.match(p.status.textContent,/Storage full/);
});
test('older tab cannot erase a newer saved match',async()=>{
 const saved=JSON.stringify({revision:9,save:'wd1.newer'});
 const p=await page({fresh:false,revision:1,save:''},{saved});
 assert.equal(p.stored,saved);assert.equal(p.reloads,1);
});
test('stale action reloads without replacing saved state',async()=>{
 const p=await page({fresh:false,revision:1,save:'wd1.old'},{respond:async()=>({status:409})});
 await p.submit();assert.equal(JSON.parse(p.stored).save,'wd1.old');assert.equal(p.reloads,1);
});
