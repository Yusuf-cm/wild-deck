package com.wilddeck.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Independent per-browser matches with bounded server cache and client-held encrypted saves. */
final class BrowserGameServer implements AutoCloseable {
 private static final String COOKIE="wild_deck_session";
 private static final int MAX_BODY=1_600_000,MAX_SESSIONS=64;
 private final HttpServer server;
 private final ExecutorService workers=Executors.newFixedThreadPool(8);
 private final Map<String,Session> sessions=new LinkedHashMap<>(16,.75f,true);
 private final MatchSaveCodec codec;
 private final boolean secureCookie;
 private final SecureRandom random=new SecureRandom();
 private final ObjectMapper json=new ObjectMapper();
 private static final class Session {
  HumanWebServer app=new HumanWebServer();
  long revision;
  boolean initialized;
  int inUse; // Guarded by the server registry lock, preventing eviction during a request.
 }
 BrowserGameServer(int port,MatchSaveCodec codec,boolean secureCookie)throws IOException{
  this.codec=codec;this.secureCookie=secureCookie;
  server=HttpServer.create(new InetSocketAddress("0.0.0.0",port),64);
  server.setExecutor(workers);server.createContext("/",this::handle);
 }
 void start(){server.start();}
 int port(){return server.getAddress().getPort();}
 public void close(){server.stop(0);workers.shutdownNow();}
 private String identity(HttpExchange e){
  String cookies=e.getRequestHeaders().getFirst("Cookie");
  if(cookies!=null)for(String cookie:cookies.split(";")){
   String[] pair=cookie.trim().split("=",2);
   if(pair.length==2&&pair[0].equals(COOKIE)&&pair[1].matches("[a-f0-9]{64}"))return pair[1];
  }
  byte[] bytes=new byte[32];random.nextBytes(bytes);
  String id=HexFormat.of().formatHex(bytes);
  e.getResponseHeaders().add("Set-Cookie",COOKIE+"="+id+"; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict"+(secureCookie?"; Secure":""));
  return id;
 }
 private synchronized Session session(String id){
  Session s=sessions.get(id);
  if(s==null){
   if(sessions.size()>=MAX_SESSIONS){
    String victim=null;
    for(var entry:sessions.entrySet())if(entry.getValue().inUse==0){victim=entry.getKey();break;}
    if(victim==null)return null;
    sessions.remove(victim);
   }
   s=new Session();sessions.put(id,s);
  }
  s.inUse++;
  return s;
 }
 private synchronized void release(Session s){s.inUse--;}
 private Map<String,Object> state(String id,Session s){
  var match=s.app.snapshot();
  return Map.of("revision",s.revision,"fresh",!s.initialized,"save",match==null?"":codec.seal(id,
      new MatchSaveCodec.Save(1,s.revision,s.app.message(),match)));
 }
 private void handle(HttpExchange e)throws IOException{
  Session usedSession=null;
  e.getResponseHeaders().set("Cache-Control","no-store");
  e.getResponseHeaders().set("X-Content-Type-Options","nosniff");
  e.getResponseHeaders().set("Referrer-Policy","same-origin");
  e.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
  try{
   String path=e.getRequestURI().getPath(),method=e.getRequestMethod();
   if(path.equals("/health")&&method.equals("GET")){send(e,200,"text/plain","ok");return;}
   boolean read=method.equals("GET")&&(path.equals("/")||path.equals("/state"));
   boolean write=method.equals("POST")&&(path.equals("/action")||path.equals("/restore"));
   if(!read&&!write){send(e,404,"text/plain","Not found");return;}
   if(write&&!"1".equals(e.getRequestHeaders().getFirst("X-Wild-Deck"))){
    send(e,403,"text/plain","Use the same-origin game controls");return;
   }
   String id=identity(e);Session s=session(id);
   if(s==null){send(e,503,"text/plain","Server is busy. Keep your browser save and retry.");return;}
   usedSession=s;
   synchronized(s){
    if(read){
     if(path.equals("/state"))sendJson(e,200,state(id,s));
     else {
      String boot=json.writeValueAsString(state(id,s)).replace("<","\\u003c");
      String page=s.app.render().replace("</body>","<p id='save-status' role='status'>Checking browser save…</p><script>const boot="+boot+";\n"+SCRIPT+"</script></body>");
      send(e,200,"text/html",page);
     }
     return;
    }
    byte[] body=e.getRequestBody().readNBytes(MAX_BODY+1);
    if(body.length>MAX_BODY){send(e,413,"text/plain","Request too large");return;}
    com.fasterxml.jackson.databind.JsonNode input;
    try{input=json.readTree(body);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){
     throw new IllegalArgumentException("Invalid JSON");
    }
    if(input==null||!input.isObject())throw new IllegalArgumentException("Expected an object");
    if(path.equals("/restore")){
     if(s.initialized){sendJson(e,409,state(id,s));return;}
     if(!input.path("save").isTextual())throw new IllegalArgumentException("Missing save");
     var save=codec.open(id,input.get("save").textValue());
     // Construct and validate the complete replacement before changing the live session.
     HumanWebServer restored=new HumanWebServer();restored.restore(save);
     s.app=restored;s.revision=save.revision();s.initialized=true;
    }else{
     if(!input.path("revision").isIntegralNumber()||!input.path("revision").canConvertToLong()||
        input.get("revision").longValue()!=s.revision){sendJson(e,409,state(id,s));return;}
     if(!input.path("command").isTextual())throw new IllegalArgumentException("Missing command");
     String command=input.get("command").textValue().trim();
     if(command.isBlank()||command.length()>250)throw new IllegalArgumentException("Order must contain 1–250 characters");
     s.app.command(command);s.revision++;s.initialized=true;
    }
    sendJson(e,200,state(id,s));
   }
  }catch(IllegalArgumentException e1){send(e,400,"text/plain","Invalid request or save. Your existing browser save was preserved.");}
   catch(Exception e1){send(e,500,"text/plain","Could not complete this request. Keep your browser save and reload.");}
  finally{if(usedSession!=null)release(usedSession);e.close();}
 }
 private void sendJson(HttpExchange e,int status,Object value)throws IOException{
  send(e,status,"application/json",json.writeValueAsString(value));
 }
 private static void send(HttpExchange e,int status,String type,String text)throws IOException{
  byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
  e.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");e.sendResponseHeaders(status,bytes.length);
  try(OutputStream out=e.getResponseBody()){out.write(bytes);}
 }
 private static final String SCRIPT="""
 (() => {
  const key='wild-deck.save.v1', status=document.getElementById('save-status');
  const controls=[...document.querySelectorAll('input,button')];
  let ready=false;
  function enable(value){controls.forEach(c=>c.disabled=!value);ready=value;}
  function persist(state){
   const old=localStorage.getItem(key);
   if(old&&JSON.parse(old).revision>state.revision){location.reload();return;}
   if(state.save){
    if(!old||JSON.parse(old).revision<=state.revision)
     localStorage.setItem(key,JSON.stringify({revision:state.revision,save:state.save}));
    status.textContent='Match saved in this browser. Keep site data to restore after a server restart.';
   }else{
    localStorage.removeItem(key);
    status.textContent='Temporary sandbox: start a quick or full match to autosave.';
   }
  }
  async function post(path,data){
   const response=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-Wild-Deck':'1'},body:JSON.stringify(data)});
   if(response.status===409){location.reload();throw Error('Another tab changed this match. Reloading…');}
   if(!response.ok)throw Error(await response.text());
   return await response.json();
  }
  function failure(error){
   enable(false);
   status.textContent='Save/restore stopped: '+error.message+' Your previous save is kept. Retry by reloading, or explicitly discard it to start again.';
   const button=document.createElement('button');
   button.textContent='Discard browser save and start again';
   button.onclick=async()=>{
    if(!confirm('Discard the saved match in this browser?'))return;
    try{await post('/action',{revision:boot.revision,command:'reset sandbox'});localStorage.removeItem(key);location.reload();}
    catch(error){status.textContent='Could not discard save: '+error.message;}
   };
   status.append(' ',button);
  }
  enable(false);
  (async()=>{
   const raw=localStorage.getItem(key);
   if(boot.fresh&&raw){
    const stored=JSON.parse(raw);
    if(typeof stored.save!=='string')throw Error('Invalid browser save');
    const restored=await post('/restore',{save:stored.save});
    persist(restored);location.reload();return;
   }
   persist(boot);enable(true);
  })().catch(failure);
  document.querySelectorAll('form').forEach(form=>form.addEventListener('submit',async event=>{
   event.preventDefault();if(!ready)return;
   const command=new FormData(form).get('command');enable(false);
   try{
    const state=await post('/action',{revision:boot.revision,command});
    persist(state);location.reload();
   }catch(error){failure(error);}
  }));
 })();
 """;
}
