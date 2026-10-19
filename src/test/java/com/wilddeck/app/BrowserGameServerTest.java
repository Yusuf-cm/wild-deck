package com.wilddeck.app;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowserGameServerTest {
 private static final byte[] KEY=new byte[32]; // Fixed test fixture only; production requires a random env key.
 private static final ObjectMapper JSON=new ObjectMapper();
 private static final class Browser {
  final HttpClient client=HttpClient.newHttpClient();
  int port;String cookie="";
  Browser(int port){this.port=port;}
  HttpResponse<String> request(String method,String path,Object data,boolean header)throws Exception{
   var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
   if(!cookie.isEmpty())builder.header("Cookie",cookie);
   if(header)builder.header("X-Wild-Deck","1");
   if(method.equals("POST"))builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(data)));
   else builder.GET();
   var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
   response.headers().firstValue("Set-Cookie").ifPresent(c->cookie=c.split(";")[0]);
   return response;
  }
  JsonNode state()throws Exception{
   var r=request("GET","/state",null,false);assertEquals(200,r.statusCode());return JSON.readTree(r.body());
  }
  JsonNode action(String command)throws Exception{
   var r=request("POST","/action",Map.of("revision",state().get("revision").asLong(),"command",command),true);
   assertEquals(200,r.statusCode(),r.body());return JSON.readTree(r.body());
  }
  MatchSaveCodec.Save decode(JsonNode state){return new MatchSaveCodec(KEY).open(cookie.substring(cookie.indexOf('=')+1),state.get("save").asText());}
 }
 @Test void twoBrowsersAreIndependentAndStaleOrCrossSessionRequestsCannotMutate()throws Exception{
  try(var server=new BrowserGameServer(0,new MatchSaveCodec(KEY),false)){
   server.start();var a=new Browser(server.port());var b=new Browser(server.port());
   var aStart=a.action("new quick game 42");var bStart=b.action("new four player game 105");
   assertNotEquals(a.cookie,b.cookie);
   assertTrue(a.decode(aStart).match().quickMatch());assertFalse(b.decode(bStart).match().quickMatch());
   var untouched=b.decode(bStart);a.action("pass");assertEquals(untouched,b.decode(b.state()));
   var before=a.decode(a.state());
   var stale=a.request("POST","/action",Map.of("revision",1,"command","pass"),true);
   assertEquals(409,stale.statusCode());assertEquals(before,a.decode(a.state()));
   assertEquals(403,a.request("POST","/action",Map.of("revision",2,"command","pass"),false).statusCode());
   assertEquals(before,a.decode(a.state()));
   var third=new Browser(server.port());third.state();
   assertEquals(400,third.request("POST","/restore",Map.of("save",aStart.get("save").asText()),true).statusCode());
   assertTrue(third.state().get("fresh").asBoolean());
   assertEquals(409,a.request("POST","/restore",Map.of("save",aStart.get("save").asText()),true).statusCode());
   assertEquals(before,a.decode(a.state()));
   var page=a.request("GET","/",null,false);
   assertTrue(page.body().contains("Private browser session"));
   assertFalse(page.body().contains("\"deck\""));assertFalse(page.body().contains("\"hand\""));
  }
 }
 @Test void evictedMatchRestoresAndMalformedRequestsPreserveState()throws Exception{
  try(var server=new BrowserGameServer(0,new MatchSaveCodec(KEY),false)){
   server.start();var browser=new Browser(server.port());
   var state=browser.action("new quick game 42");var expected=browser.decode(state);
   for(int i=0;i<64;i++)new Browser(server.port()).state();
   assertTrue(browser.state().get("fresh").asBoolean());
   assertEquals(200,browser.request("POST","/restore",Map.of("save",state.get("save").asText()),true).statusCode());
   assertEquals(expected,browser.decode(browser.state()));
   assertEquals(400,browser.request("POST","/action",null,true).statusCode());
   assertEquals(400,browser.request("POST","/action",Map.of("revision",1,"command",""),true).statusCode());
   assertEquals(413,browser.request("POST","/restore",Map.of("save","x".repeat(1_600_001)),true).statusCode());
   assertEquals(expected,browser.decode(browser.state()));
  }
 }
 @Test void sealedSavesRejectTamperingUnknownVersionWrongKeyAndForeignBrowser()throws Exception{
  var codec=new MatchSaveCodec(KEY);
  var save=new MatchSaveCodec.Save(1,12,"saved",RndMatchEngine.startQuick(42).snapshot());
  String token=codec.seal("first",save);assertEquals(save,codec.open("first",token));
  assertThrows(IllegalArgumentException.class,()->codec.open("other",token));
  String changed=token.substring(0,30)+(token.charAt(30)=='A'?"B":"A")+token.substring(31);
  assertThrows(IllegalArgumentException.class,()->codec.open("first",changed));
  byte[] other=KEY.clone();other[0]=1;
  assertThrows(IllegalArgumentException.class,()->new MatchSaveCodec(other).open("first",token));
  String unsupported=codec.seal("first",new MatchSaveCodec.Save(2,12,"saved",save.match()));
  assertThrows(IllegalArgumentException.class,()->codec.open("first",unsupported));
 }
 @Test void twoMatchesSurviveAnActualJvmRestartAndContinueIdentically()throws Exception{
  Process first=null,second=null;
  try{
   var launch=startProcess();first=launch.process();
   var a=new Browser(launch.port());var b=new Browser(launch.port());
   a.action("new quick game 42");a.action("offer asha peace 3");var aState=a.action("pass");
   b.action("new four player game 105");var bState=b.action("draw");
   var aSave=a.decode(aState);var bSave=b.decode(bState);
   first.destroy();assertTrue(first.waitFor(10,TimeUnit.SECONDS));first=null;
   launch=startProcess();second=launch.process();a.port=launch.port();b.port=launch.port();
   assertTrue(a.state().get("fresh").asBoolean());assertTrue(b.state().get("fresh").asBoolean());
   assertEquals(400,a.request("POST","/restore",Map.of("save","wd1.corrupt"),true).statusCode());
   assertTrue(a.state().get("fresh").asBoolean());
   assertEquals(200,a.request("POST","/restore",Map.of("save",aState.get("save").asText()),true).statusCode());
   assertEquals(200,b.request("POST","/restore",Map.of("save",bState.get("save").asText()),true).statusCode());
   assertEquals(aSave,a.decode(a.state()));assertEquals(bSave,b.decode(b.state()));
   var expected=new HumanWebServer();expected.restore(aSave);expected.command("pass");
   var continued=a.decode(a.action("pass"));
   assertEquals(expected.snapshot(),continued.match());assertEquals(expected.message(),continued.message());
   assertEquals(bSave,b.decode(b.state()));
  }finally{if(first!=null)first.destroyForcibly();if(second!=null)second.destroyForcibly();}
 }
 private record Launch(Process process,int port){}
 private Launch startProcess()throws Exception{
  String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
  var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
      "-cp",classpath,HumanWebServer.class.getName()).redirectErrorStream(true);
  builder.environment().put("PORT","0");
  builder.environment().put("WILD_DECK_COOKIE_SECURE","false");
  builder.environment().put("WILD_DECK_SAVE_KEY",Base64.getEncoder().encodeToString(KEY));
  Process process=builder.start();
  var reader=new BufferedReader(new InputStreamReader(process.getInputStream()));
  var output=CompletableFuture.supplyAsync(()->{
   try{String line;while((line=reader.readLine())!=null){
    if(line.startsWith("Wild Deck Human Alpha listening on "))return Integer.parseInt(line.substring(line.lastIndexOf(' ')+1));
   }throw new IllegalStateException("Server exited before listening");}
   catch(IOException e){throw new UncheckedIOException(e);}
  });
  try{return new Launch(process,output.get(20,TimeUnit.SECONDS));}
  catch(Exception e){process.destroyForcibly();throw e;}
 }
}
