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
 @Test void roundOneDrawAfterArcaneTowerProducesReproducibleCard(){
  RndMatchEngine g=RndMatchEngine.start(20261010);
  assertTrue(g.command("player","deploy Arcane Tower").success());
  assertTrue(g.command("player","pass").success());
  g.takeOpponentTurns();
  assertEquals(1,g.round());
  var draw=g.command("player","draw");
  assertTrue(draw.success());
  System.out.println("RND_ROUND1_PLAYER_DRAW: "+draw.message());
  assertFalse(g.command("player","deploy Mind Veil").success());
 }
 @Test void roundOneAfterGemstoneDrawOpponentsAndTowerProduction(){
  RndMatchEngine g=RndMatchEngine.start(20261010);
  assertTrue(g.command("player","deploy Arcane Tower").success());
  assertTrue(g.command("player","pass").success());
  g.takeOpponentTurns();
  assertEquals(1,g.round());
  assertTrue(g.command("player","draw").success());
  assertTrue(g.command("player","pass").success());
  var moves=g.takeOpponentTurns();
  System.out.println("RND_ROUND1_NPC_MOVES: "+moves);
  System.out.println("RND_ROUND2_PLAYER_STATE: "+g.view("player"));
  assertEquals(2,g.round());
  assertEquals(2,g.seat("player").mana);
  assertEquals("player",g.activeSeat());
  assertEquals(171,g.deckSize());
 }
 @Test void seededRoundTwoDrawIsStable(){
  RndMatchEngine g=RndMatchEngine.start(20261010);
  assertTrue(g.command("player","deploy Arcane Tower").success());
  assertTrue(g.command("player","pass").success());g.takeOpponentTurns();
  assertTrue(g.command("player","draw").success());
  assertTrue(g.command("player","pass").success());g.takeOpponentTurns();
  var result=g.command("player","draw");
  assertTrue(result.success());
  System.out.println("RND_ROUND2_DRAW: "+result.message());
  assertFalse(g.command("player","draw").success());
  assertFalse(g.command("player","deploy Gemstone").success());
 }
 @Test void roundTwoAfterMagicCrystalDrawResolvesNpcTurns(){
  RndMatchEngine g=RndMatchEngine.start(20261010);
  assertTrue(g.command("player","deploy Arcane Tower").success());
  assertTrue(g.command("player","pass").success());g.takeOpponentTurns();
  assertTrue(g.command("player","draw").success());
  assertTrue(g.command("player","pass").success());g.takeOpponentTurns();
  assertEquals("Drew Magic Crystal [WD-146]",g.command("player","draw").message());
  assertTrue(g.command("player","pass").success());
  var moves=g.takeOpponentTurns();
  System.out.println("RND_ROUND2_NPC_MOVES: "+moves);
  System.out.println("RND_ROUND3_PLAYER_STATE: "+g.view("player"));
  assertEquals(3,g.round());
  assertEquals(3,g.seat("player").mana);
  assertEquals(170,g.deckSize());
 }
 @Test void roundThreePlayerDrawAfterOpponentTurns(){
  RndMatchEngine g=RndMatchEngine.start(20261010);
  assertTrue(g.command("player","deploy Arcane Tower").success());
  assertTrue(g.command("player","pass").success());g.takeOpponentTurns();
  assertTrue(g.command("player","draw").success());
  assertTrue(g.command("player","pass").success());g.takeOpponentTurns();
  assertEquals("Drew Magic Crystal [WD-146]",g.command("player","draw").message());
  assertTrue(g.command("player","pass").success());g.takeOpponentTurns();
  assertEquals(3,g.round());
  var result=g.command("player","draw");
  assertTrue(result.success());
  System.out.println("RND_ROUND3_DRAW: "+result.message());
  assertFalse(g.command("player","deploy Mind Veil").success());
 }
 @Test void roundThreePassLeadsToRoundFourAndNoAutomaticElimination(){
  RndMatchEngine g=RndMatchEngine.start(20261010);
  assertTrue(g.command("player","deploy Arcane Tower").success());
  assertTrue(g.command("player","pass").success());g.takeOpponentTurns();
  assertTrue(g.command("player","draw").success());
  assertTrue(g.command("player","pass").success());g.takeOpponentTurns();
  assertEquals("Drew Magic Crystal [WD-146]",g.command("player","draw").message());
  assertTrue(g.command("player","pass").success());g.takeOpponentTurns();
  assertEquals("Drew Beast Saddle [WD-139]",g.command("player","draw").message());
  assertTrue(g.command("player","pass").success());
  var moves=g.takeOpponentTurns();
  System.out.println("RND_ROUND3_NPC_MOVES: "+moves);
  System.out.println("RND_ROUND4_PLAYER_STATE: "+g.view("player"));
  assertEquals(4,g.round());
  assertEquals("player",g.activeSeat());
  assertEquals(4,g.seat("player").mana);
  assertEquals(169,g.deckSize());
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
