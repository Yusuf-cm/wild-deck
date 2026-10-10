package com.wilddeck.app;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgeOfChaosEngineTest {
 @Test void openingWitchAndEmberfallResponse(){
  AgeOfChaosEngine g=AgeOfChaosEngine.newGame();
  assertTrue(g.play("thornveil","The Witch of Hollow Roots").success());
  assertEquals(3,g.kingdom("thornveil").wealth);
  assertEquals(2,g.kingdom("thornveil").mana);
  assertEquals(3,g.kingdom("emberfall").wealth);
  assertTrue(g.pass("thornveil").success());
  assertEquals("emberfall",g.turn());
  assertTrue(g.play("emberfall","Ashen Treasury").success());
  assertTrue(g.pass("emberfall").success());
  assertEquals("sunspire",g.turn());
  assertEquals(6,g.kingdom("thornveil").hand.size());
  System.out.println("AGE_OF_CHAOS_R1: "+g.history());
 }
 @Test void freshMatchStartsWithSevenForEveryKingdomAndEmptyBoards(){
  AgeOfChaosEngine g=AgeOfChaosEngine.newGame();
  assertEquals(1,g.round());
  assertEquals("thornveil",g.turn());
  assertEquals(Set.of("thornveil","sunspire"),AgeOfChaosEngine.HUMAN_KINGDOMS);
  assertEquals(Set.of("emberfall","dreadhaven"),AgeOfChaosEngine.AI_KINGDOMS);
  for(String id:AgeOfChaosEngine.TURN_ORDER){
   assertEquals(7,g.kingdom(id).hand.size());
   assertEquals(0,g.kingdom(id).board.size());
   assertEquals(3,g.kingdom(id).wealth);
   assertEquals(2,g.kingdom(id).mana);
  }
  assertEquals(28,g.openingHandSizes().values().stream().mapToInt(Integer::intValue).sum());
 }
 @Test void eachKingdomOnlySeesTheirOwnHandAndCannotActForAnother(){
  AgeOfChaosEngine g=AgeOfChaosEngine.newGame();
  assertFalse(g.dashboard("thornveil").contains("Cinderbound Captain"));
  assertFalse(g.dashboard("sunspire").contains("The Witch of Hollow Roots"));
  assertFalse(g.play("emberfall","Cinderbound Captain").success());
  assertTrue(g.play("thornveil","Lantern Thief").success());
  assertFalse(g.play("thornveil","Whispermoth Swarm").success());
  assertEquals(3,g.kingdom("thornveil").wealth);
  assertTrue(g.pass("thornveil").success());
  assertEquals("emberfall",g.turn());
 }
 @Test void roundWrapGivesAllWealthAndProducedItems(){
  AgeOfChaosEngine g=AgeOfChaosEngine.newGame();
  assertTrue(g.play("thornveil","Orchard of Second Chances").success());
  assertTrue(g.pass("thornveil").success());
  assertTrue(g.play("emberfall","Ashen Treasury").success());
  assertTrue(g.pass("emberfall").success());
  assertTrue(g.play("sunspire","Heartforge").success());
  assertTrue(g.pass("sunspire").success());
  assertTrue(g.pass("dreadhaven").success());
  assertEquals(2,g.round());
  assertEquals("thornveil",g.turn());
  assertEquals(4,g.kingdom("thornveil").wealth);
  assertEquals(4,g.kingdom("emberfall").wealth);
  assertEquals(4,g.kingdom("sunspire").wealth);
  assertEquals(4,g.kingdom("dreadhaven").wealth);
  assertEquals(1,g.kingdom("thornveil").inventory.get("Timber"));
  assertEquals(1,g.kingdom("sunspire").inventory.get("Iron"));
  assertEquals(2,g.kingdom("emberfall").gold);
 }
}
