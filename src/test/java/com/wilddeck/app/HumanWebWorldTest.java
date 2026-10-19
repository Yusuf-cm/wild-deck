package com.wilddeck.app;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HumanWebWorldTest {
 @Test void resetSandboxExitsEveryModeAndRestoresSandboxRouting() throws Exception {
  var app=new HumanWebServer();
  var act=HumanWebServer.class.getDeclaredMethod("act",String.class);act.setAccessible(true);
  var render=HumanWebServer.class.getDeclaredMethod("render");render.setAccessible(true);
  for(String start:java.util.List.of("new quick game 42","new four player game 42",
      "load cycle 18","replay rounds 0-18","run rules replay")) {
   act.invoke(app,start);
   assertTrue(((String)act.invoke(app,"reset sandbox")).contains("Returned to basic sandbox"));
   String page=(String)render.invoke(app);
   assertTrue(page.contains("Kingdom Treasury"),start);
   assertFalse(page.contains("<h2>Wild Deck — Quick Match</h2>"),start);
   assertTrue(((String)act.invoke(app,"world")).contains("World locations:"),start);
  }
  act.invoke(app,"new quick game 42");
  assertTrue(((String)render.invoke(app)).contains("<h2>Wild Deck — Quick Match</h2>"));
 }

 @Test void quickMatchShowsItsActualSupportedPool() throws Exception {
  var app=new HumanWebServer();var act=HumanWebServer.class.getDeclaredMethod("act",String.class);act.setAccessible(true);
  var render=HumanWebServer.class.getDeclaredMethod("render");render.setAccessible(true);
  assertTrue(((String)act.invoke(app,"new quick game 42")).contains("New quick match"));
  String page=(String)render.invoke(app);assertTrue(page.contains("Quick Match"));assertTrue(page.contains("55-card seeded deck"));
  String cards=(String)act.invoke(app,"cards");assertFalse(cards.contains("effect not implemented"));
 }

 @Test void webCommandsAndHeaderReadTheActiveMatch() throws Exception {
  var app=new HumanWebServer();
  var act=HumanWebServer.class.getDeclaredMethod("act",String.class);act.setAccessible(true);
  var render=HumanWebServer.class.getDeclaredMethod("render");render.setAccessible(true);
  act.invoke(app,"new four player game 20261010");
  assertTrue(((String)act.invoke(app,"new four player game invalid")).contains("current match unchanged"));
  assertTrue(((String)act.invoke(app,"world")).contains("W1 Runebank"));
  String result=(String)act.invoke(app,"pass");
  assertTrue(result.contains("Active: player; round 1"));
  assertTrue(result.contains("\nOpponent moves:"));
  String page=(String)render.invoke(app);
  assertTrue(page.contains("1 · Private browser session"));
  assertTrue(page.contains("Round 1 | Current: player"));
  act.invoke(app,"pass");
  assertTrue(((String)render.invoke(app)).contains("2 · Private browser session"));
 }
}
