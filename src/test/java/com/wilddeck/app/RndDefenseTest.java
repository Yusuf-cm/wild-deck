package com.wilddeck.app;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class RndDefenseTest {
 private RndMatchEngine.Card troop(String id,int strength){return new RndMatchEngine.Card(id,id,"Military",Set.of("ATTACK","DEFEND","OCCUPY"),strength);}
 private void passOtherSeats(RndMatchEngine g){for(String id:List.of("asha","brian","mira"))if(!g.eliminated(id))assertTrue(g.command(id,"pass").success());}
 private RndMatchEngine readyBattle(int attack,int defense){
  var g=RndMatchEngine.start(836);
  g.seat("player").hand.put("army",troop("army",attack));g.command("player","deploy army");g.command("player","move army C1");g.command("player","pass");
  if(defense>0){g.seat("asha").hand.put("guard",troop("guard",defense));g.command("asha","deploy guard");}
  passOtherSeats(g);g.command("player","move army N1");return g;
 }
 @Test void simultaneousDamageWoundsSurvivorAndRemovesDefender(){
  var g=readyBattle(8,3);assertTrue(g.command("player","attack asha").success());
  assertEquals(5,g.remainingStrength("army"));assertFalse(g.seat("asha").board.containsKey("guard"));
  assertTrue(g.cardLocation("guard").isEmpty());assertEquals(1,g.discardedCount());
  assertFalse(g.command("player","attack asha").success());
 }
 @Test void equalForcesDestroyEachOtherWithoutAnOccupation(){
  var g=readyBattle(3,3);g.command("player","attack asha");
  assertEquals(0,g.remainingStrength("army"));assertEquals(0,g.remainingStrength("guard"));
  assertFalse(g.command("player","conquer asha").success());assertFalse(g.eliminated("asha"));
 }
 @Test void defenderResponseBreaksOccupation(){
  var g=readyBattle(8,0);g.command("player","attack asha");assertTrue(g.command("player","conquer asha").success());
  assertFalse(g.eliminated("asha"));g.command("player","pass");
  g.seat("asha").hand.put("reinforcement",troop("reinforcement",10));g.command("asha","deploy reinforcement");
  assertTrue(g.command("asha","repel player").success());passOtherSeats(g);
  assertFalse(g.eliminated("asha"));assertEquals(0,g.remainingStrength("army"));
  assertTrue(g.events().stream().anyMatch(e->e.contains("occupation of asha broken")));
 }
 @Test void conquestWaitsForDefendersActualTurnEvenWhenQueuedLateInRound(){
  var g=RndMatchEngine.start(836);g.command("player","pass");g.command("asha","pass");g.command("brian","pass");
  g.seat("mira").hand.put("army",troop("army",20));g.command("mira","deploy army");g.command("mira","move army C1");g.command("mira","pass");
  for(String id:List.of("player","asha","brian"))g.command(id,"pass");
  g.command("mira","move army N1");g.command("mira","attack asha");assertTrue(g.command("mira","conquer asha").success());g.command("mira","pass");
  assertEquals(2,g.round());assertFalse(g.eliminated("asha"));
  for(String id:List.of("player","asha","brian","mira"))g.command(id,"pass");
  assertTrue(g.eliminated("asha"));
 }
 @Test void finalConquestDeclaresWinnerAndLocksMutation(){
  var g=readyBattle(20,0);g.command("player","attack asha");g.command("player","conquer asha");g.command("player","pass");passOtherSeats(g);
  assertTrue(g.eliminated("asha"));assertTrue(g.winner().isEmpty());
  g.command("player","move army N2");g.command("player","attack brian");g.command("player","conquer brian");g.command("player","pass");passOtherSeats(g);
  assertTrue(g.eliminated("brian"));assertEquals("player",g.activeSeat());
  g.command("player","move army E1");g.command("player","attack mira");g.command("player","conquer mira");g.command("player","pass");passOtherSeats(g);
  assertEquals("player",g.winner().orElseThrow());assertFalse(g.command("player","draw").success());
  assertFalse(g.command("player","pass").success());assertTrue(g.command("mira","world").success());
  assertTrue(g.view("player").contains("WINNER player"));
  assertTrue(g.worldView("player").contains("N1 Moonwood [Forest] owner=player"));
 }
 @Test void opponentsCounterFireUsingOwnedWaterAndReinforceAgainstInvaders(){
  var g=RndMatchEngine.start(836);g.command("player","use Wildfire on N1");
  g.seat("player").hand.remove("WD-165");
  g.seat("asha").hand.put("water",new RndMatchEngine.Card("water","Rain","Event",Set.of("WORLD_EFFECT","EXTINGUISH"),0,Set.of("WATER")));
  g.command("player","pass");var response=g.takeOpponentTurns();assertFalse(g.isBurning("N1"));
  assertTrue(response.stream().anyMatch(r->r.contains("extinguished N1")));
  var invasion=readyBattle(2,0);invasion.seat("asha").hand.put("defender",troop("defender",8));
  invasion.command("player","pass");var defenses=invasion.takeOpponentTurns();
  assertTrue(defenses.stream().anyMatch(r->r.contains("defensive reinforcement")));
  assertEquals(0,invasion.remainingStrength("army"));
 }
}
