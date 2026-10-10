package com.wilddeck.app;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class RndMatchEngineTest {
 @Test void fourSeatsStartEmptyWithSevenPrivateCardsAndTwoHundredCardDeck(){
  RndMatchEngine g=RndMatchEngine.start(20261010);
  assertEquals(172,g.deckSize());
  assertEquals(0,g.round());
  for(String id:List.of("player","asha","brian","mira")){
   assertEquals(7,g.seat(id).hand.size());
   assertEquals(0,g.seat(id).board.size());
  }
  String visible=g.view("player");
  assertTrue(visible.contains("hidden cards"));
  assertFalse(visible.contains(g.seat("asha").hand.values().iterator().next().id()));
  System.out.println("RND_MATCH_OPENING_HAND: "+g.seat("player").hand.values().stream().map(c->c.name()+" ["+c.id()+"]").toList());
 }
 @Test void playerPlaysAndDrawsAndCannotSeeHiddenOrStealTurns(){
  RndMatchEngine g=RndMatchEngine.start(20261010);
  String first=g.seat("player").hand.keySet().iterator().next();
  assertTrue(g.command("player","deploy "+first).success());
  assertEquals(1,g.seat("player").board.size());
  assertFalse(g.command("player","draw a card").success());
  assertFalse(g.command("player","draw").success());
  assertFalse(g.command("asha","draw").success());
  assertTrue(g.command("player","pass").success());
  assertEquals("asha",g.activeSeat());
  assertTrue(g.command("asha","draw").success());
  assertTrue(g.command("asha","pass").success());
  assertTrue(g.command("brian","pass").success());
  assertTrue(g.command("mira","pass").success());
  assertEquals(1,g.round());
 }
 @Test void threeOpponentsActFromOwnedHandsAfterHumanPass(){
  RndMatchEngine g=RndMatchEngine.start(20261010);
  assertTrue(g.command("player","pass").success());
  var messages=g.takeOpponentTurns();
  assertEquals(3,messages.size());
  assertEquals(1,g.round());
  assertEquals("player",g.activeSeat());
  System.out.println("RND_OPPONENT_ACTIONS: "+messages);
  for(String id:List.of("asha","brian","mira")){
   assertEquals(1,g.seat(id).board.size());
   assertEquals(6,g.seat(id).hand.size());
  }
 }
 @Test void arcaneTowerDeploysFreeAndGeneratesManaOnlyAtRoundBoundary(){
  RndMatchEngine g=RndMatchEngine.start(20261010);
  assertTrue(g.command("player","deploy Arcane Tower").success());
  assertFalse(g.command("player","draw").success());
  assertFalse(g.command("player","deploy Blood Moon").success());
  assertTrue(g.command("player","assign Arcane Tower to guard the capital").success());
  assertEquals(0,g.seat("player").mana);
  assertFalse(g.seat("player").hand.values().stream().anyMatch(c->c.name().equals("Arcane Tower")));
  assertTrue(g.seat("player").board.values().stream().anyMatch(c->c.name().equals("Arcane Tower")));
  assertTrue(g.command("player","pass").success());
  assertEquals(0,g.seat("player").mana);
  g.takeOpponentTurns();
  assertEquals(1,g.round());
  assertEquals(1,g.seat("player").mana);
 }
 @Test void unimplementedAbilityCannotInventEffect(){
  RndMatchEngine g=RndMatchEngine.start(20261010);
  var card=g.seat("player").hand.values().iterator().next();
  assertTrue(g.command("player","deploy "+card.id()).success());
  int mana=g.seat("player").mana;
  var action=new RndMatchEngine.Order("player","ABILITY",card.id(),null,
      card.capabilities().iterator().next(),Map.of());
  assertFalse(g.execute(action).success());
  assertEquals(mana,g.seat("player").mana);
 }
}
