package com.wilddeck.app;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HumanWebWorldTest {
 @Test void webCommandsAndHeaderReadTheActiveMatch() throws Exception {
  var app=new HumanWebServer();
  var act=HumanWebServer.class.getDeclaredMethod("act",String.class);act.setAccessible(true);
  var render=HumanWebServer.class.getDeclaredMethod("render");render.setAccessible(true);
  act.invoke(app,"new four player game");
  assertTrue(((String)act.invoke(app,"world")).contains("W1 Runebank"));
  String result=(String)act.invoke(app,"pass");
  assertTrue(result.contains("Active: player; round 1"));
  assertTrue(result.contains("\nOpponent moves:"));
  String page=(String)render.invoke(app);
  assertTrue(page.contains("1 · Single shared demo session"));
  assertTrue(page.contains("Round 1 | Current: player"));
  act.invoke(app,"pass");
  assertTrue(((String)render.invoke(app)).contains("2 · Single shared demo session"));
 }
}
