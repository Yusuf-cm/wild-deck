package com.wilddeck.app;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class RndTerrainTest {
 private RndMatchEngine.Card unit(String id,boolean resistant){return new RndMatchEngine.Card(id,"Scout "+id,"Military",Set.of("MOVE","ATTACK"),5,resistant?Set.of("FIREPROOF"):Set.of());}
 private void finishRound(RndMatchEngine g){for(String id:List.of("player","asha","brian","mira"))assertTrue(g.command(id,"pass").success());}
 @Test void actualWildfireChangesTerrainAndConsumesOnlyItsCardAction(){
  var g=RndMatchEngine.start(20261010L);
  assertTrue(g.command("player","use Wildfire on N1").success());
  assertTrue(g.isBurning("N1"));assertEquals(1,g.discardedCount());
  assertFalse(g.seat("player").hand.containsKey("WD-171"));
  assertTrue(g.command("player","world").message().contains("BURNING"));
  assertFalse(g.command("player","draw").success());
 }
 @Test void invalidTerrainTargetDoesNotSpendCardOrTurn(){
  var g=RndMatchEngine.start(20261010L);
  assertFalse(g.command("player","use Wildfire on W1").success());
  assertFalse(g.command("player","use Wildfire on missing").success());
  assertEquals(0,g.discardedCount());assertTrue(g.seat("player").hand.containsKey("WD-171"));
  assertTrue(g.command("player","use Wildfire on N1").success());
 }
 @Test void waterCounterRestoresEntryAndFailureDoesNotSpendMovement(){
  var g=RndMatchEngine.start(20261010L);
  g.command("player","use Wildfire on N1");finishRound(g);
  g.seat("player").hand.put("scout",unit("scout",false));
  g.command("player","deploy scout");g.command("player","move scout C1");finishRound(g);
  assertFalse(g.command("player","move scout N1").success());
  assertEquals("C1",g.cardLocation("scout").orElseThrow());
  g.seat("player").hand.put("rain",new RndMatchEngine.Card("rain","Rain","Event",Set.of("WORLD_EFFECT","EXTINGUISH"),0,Set.of("WATER")));
  assertTrue(g.command("player","use rain on N1").success());assertFalse(g.isBurning("N1"));
  assertTrue(g.command("player","move scout N1").success());
 }
 @Test void fireproofUnitCanEnterBurningRegion(){
  var g=RndMatchEngine.start(20261010L);g.command("player","use Wildfire on N1");finishRound(g);
  g.seat("player").hand.put("safe",unit("safe",true));g.command("player","deploy safe");g.command("player","move safe C1");finishRound(g);
  assertTrue(g.command("player","move safe N1").success());
 }
 @Test void burningPenaltyUsesPropertiesRatherThanCardNames(){
  var g=RndMatchEngine.start(20261010L);g.command("player","use Wildfire on N1");finishRound(g);
  g.seat("player").hand.put("safe",unit("safe",true));g.command("player","deploy safe");g.command("player","move safe C1");
  g.command("player","pass");g.seat("asha").hand.put("guard",unit("guard",false));g.command("asha","deploy guard");
  for(String id:List.of("asha","brian","mira"))g.command(id,"pass");
  g.command("player","move safe N1");
  assertTrue(g.command("player","attack asha").message().contains("Won the raid"));
 }
 @Test void remoteRaidsFailWithoutSpendingHostileAllowance(){
  var g=RndMatchEngine.start(20261010L);
  g.seat("player").hand.put("scout",unit("scout",false));g.command("player","deploy scout");
  assertFalse(g.command("player","attack asha").success());g.command("player","move scout C1");finishRound(g);
  assertFalse(g.command("player","attack asha").success());g.command("player","move scout N1");
  assertTrue(g.command("player","attack asha").success());assertFalse(g.command("player","attack asha").success());
 }
}
