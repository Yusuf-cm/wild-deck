package com.wilddeck.app;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class RndWorldIntegrationTest {
 private RndMatchEngine gameWithUnit(){
  var g=RndMatchEngine.start(20261010L);
  g.seat("player").hand.put("test-unit",new RndMatchEngine.Card("test-unit","Test Scout","Military",Set.of("MOVE"),1));
  assertTrue(g.command("player","deploy test-unit").success());
  return g;
 }
 @Test void worldInspectionAndMovementUseMatchState(){
  var g=gameWithUnit();
  assertEquals("W1",g.cardLocation("test-unit").orElseThrow());
  assertTrue(g.command("player","world").message().contains("Test Scout @ W1"));
  assertTrue(g.command("player","move Test Scout to C1").success());
  assertEquals("C1",g.cardLocation("test-unit").orElseThrow());
  assertTrue(g.view("player").contains("Test Scout @ C1"));
  assertFalse(g.command("player","move test-unit S1").success());
  assertEquals("C1",g.cardLocation("test-unit").orElseThrow());
  assertTrue(g.command("player","pass").success());g.takeOpponentTurns();
  assertTrue(g.command("player","move test-unit S1").success());
 }
 @Test void rejectedMovesDoNotConsumeMovementOrChangeState(){
  var g=gameWithUnit();
  assertFalse(g.command("player","move test-unit N1").success());
  assertFalse(g.command("player","move test-unit missing").success());
  assertFalse(g.command("player","move test-unit").success());
  assertFalse(g.command("asha","move test-unit C1").success());
  assertEquals("W1",g.cardLocation("test-unit").orElseThrow());
  assertTrue(g.command("player","move test-unit C1").success());
 }
 @Test void enemyCardsAndFixedStructuresCannotMove(){
  var g=RndMatchEngine.start(20261010L);
  assertTrue(g.command("player","deploy Arcane Tower").success());
  var tower=g.seat("player").board.values().iterator().next();
  assertFalse(g.command("player","move Arcane Tower to C1").success());
  assertEquals("W1",g.cardLocation(tower.id()).orElseThrow());
  g.command("player","pass");g.takeOpponentTurns();
  assertFalse(g.command("player","move Mercenaries to C1").success());
  assertFalse(g.command("player","world").message().contains("hidden cards"));
 }
}
