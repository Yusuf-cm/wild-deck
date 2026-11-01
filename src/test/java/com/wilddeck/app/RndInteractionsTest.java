package com.wilddeck.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class RndInteractionsTest {
 private RndMatchEngine.Card troop(String id,boolean proof){return new RndMatchEngine.Card(id,id,"Military",Set.of("ATTACK","DEFEND","OCCUPY"),5,proof?Set.of("FIREPROOF"):Set.of());}
 private RndMatchEngine.Card fire(String id,boolean magic){return new RndMatchEngine.Card(id,"Renamed fire "+id,"Event",magic?Set.of("WORLD_EFFECT","IGNITE","MAGIC"):Set.of("WORLD_EFFECT","IGNITE"),0,Set.of("FIRE"));}
 private RndMatchEngine.Card rain(){return new RndMatchEngine.Card("rain","Unrelated rain name","Event",Set.of("WORLD_EFFECT","EXTINGUISH"),0,Set.of("WATER"));}
 private void yes(RndMatchEngine g,String actor,String command){var r=g.command(actor,command);assertTrue(r.success(),command+": "+r.message());}
 private void others(RndMatchEngine g){for(String actor:List.of("asha","brian","mira"))yes(g,actor,"pass");}
 private RndMatchEngine ready(){
  var g=RndMatchEngine.startQuick(42);g.seat("player").hand.put("ember",fire("ember",false));g.seat("player").hand.put("army",troop("army",false));
  yes(g,"player","deploy army");yes(g,"player","move army C1");yes(g,"player","pass");
  g.seat("asha").hand.put("guard",troop("guard",false));yes(g,"asha","deploy guard");others(g);return g;
 }
 private String combine(){return "combine ember with army to flush N1";}
 @Test void twoCapabilitiesProduceActualRetreatAfterDefenderResponse(){
  var g=ready();yes(g,"player",combine());assertTrue(g.isBurning("N1"));assertEquals(0,g.seat("player").wealth);
  assertEquals("N1",g.cardLocation("guard").orElseThrow());assertEquals("C1",g.cardLocation("army").orElseThrow());
  assertFalse(g.seat("player").hand.containsKey("ember"));assertEquals(1,g.discardedCount());
  assertFalse(g.command("player","draw").success());assertFalse(g.command("player","attack asha").success());
  yes(g,"player","pass");others(g);assertEquals("N2",g.cardLocation("guard").orElseThrow());
  assertTrue(g.isBurning("N1"));assertTrue(g.command("player","interactions").message().contains("RETREATED N1 -> N2"));
  assertFalse(g.command("player","move army N1").success()); // own advance remains blocked
  assertEquals(5,g.remainingStrength("guard"));assertFalse(g.eliminated("asha"));
  assertFalse(g.command("player","conquer asha").success()); // no automatic foothold
  yes(g,"player","pass");assertFalse(g.command("asha","move guard C1").success()); // forced movement counted in R2
 }
 @Test void waterCancelsBeforeRetreatAndDoesNotRefundCosts(){
  var g=ready();yes(g,"player",combine());yes(g,"player","pass");
  g.seat("asha").hand.put("rain",rain());yes(g,"asha","use rain on N1");others(g);
  assertEquals("N1",g.cardLocation("guard").orElseThrow());assertFalse(g.isBurning("N1"));assertEquals(1,g.seat("player").wealth);
  assertTrue(g.command("player","interactions").message().contains("COUNTERED"));assertEquals(2,g.discardedCount());
 }
 @Test void fireproofEquipmentCanSaveDefenderDuringItsResponse(){
  var g=ready();yes(g,"player",combine());yes(g,"player","pass");
  var mail=new RndMatchEngine.Card("mail","Custom heat suit","Equipment",Set.of("ATTACH"),0,Set.of("FIREPROOF"));
  g.seat("asha").hand.put("mail",mail);yes(g,"asha","attach mail to guard");others(g);
  assertEquals("N1",g.cardLocation("guard").orElseThrow());assertEquals("N1",g.cardLocation("mail").orElseThrow());
  assertTrue(g.command("player","interactions").message().contains("no vulnerable defenders remained"));
 }
 @Test void ordinaryEquipmentTravelsWithForcedRetreat(){
  var g=ready();yes(g,"player",combine());yes(g,"player","pass");
  var shield=new RndMatchEngine.Card("shield","Shield","Equipment",Set.of("ATTACH"),0,Set.of(),Map.of("ARMOR",2));
  g.seat("asha").hand.put("shield",shield);yes(g,"asha","attach shield to guard");others(g);
  assertEquals("N2",g.cardLocation("guard").orElseThrow());assertEquals("N2",g.cardLocation("shield").orElseThrow());
  assertEquals("guard",g.attachedTo("shield").orElseThrow());assertTrue(g.seat("asha").board.containsKey("guard"));
 }
 @Test void fixedAssetsAndFireproofUnitsAreNotTeleported(){
  var g=ready();g.seat("asha").hand.put("proof",troop("proof",true));
  yes(g,"player","pass");yes(g,"asha","deploy proof");others(g);
  yes(g,"player",combine());yes(g,"player","pass");g.seat("asha").hand.put("store",new RndMatchEngine.Card("store","Store","Structure",Set.of("PRODUCE"),0));yes(g,"asha","deploy store");others(g);
  assertEquals("N2",g.cardLocation("guard").orElseThrow());assertEquals("N1",g.cardLocation("proof").orElseThrow());assertEquals("N1",g.cardLocation("store").orElseThrow());
 }
 @Test void blockedEscapePinsInsteadOfKillingOrCapturing(){
  var g=ready();yes(g,"player",combine());yes(g,"player","pass");yes(g,"asha","pass");
  g.seat("brian").hand.put("block",troop("block",false));yes(g,"brian","deploy block");yes(g,"brian","pass");yes(g,"mira","pass");
  assertEquals("N1",g.cardLocation("guard").orElseThrow());assertEquals(5,g.remainingStrength("guard"));
  assertTrue(g.command("player","interactions").message().contains("PINNED"));assertEquals(1,g.discardedCount());
 }
 @Test void defenderCanMoveAwayDuringItsActualTurn(){
  var g=ready();yes(g,"player",combine());yes(g,"player","pass");yes(g,"asha","move guard C1");others(g);
  assertEquals("C1",g.cardLocation("guard").orElseThrow());assertTrue(g.command("player","interactions").message().contains("no vulnerable defenders remained"));
 }
 @Test void losingOrMovingSupportCancelsPressureButLeavesOrdinaryFire(){
  for(boolean remove:List.of(false,true)){
   var g=ready();yes(g,"player",combine());
   if(remove)g.seat("player").board.remove("army");else yes(g,"player","move army S1");
   yes(g,"player","pass");others(g);
   assertEquals("N1",g.cardLocation("guard").orElseThrow());assertTrue(g.isBurning("N1"));assertTrue(g.command("player","interactions").message().contains("support lost, moved or ineffective"));
  }
 }
 @Test void invalidInputsNeverSpendSourceSuppliesOrAction(){
  for(String command:List.of("combine ember","combine ember with missing to flush N1","combine missing with army to flush N1","combine ember with army to flush nowhere","combine ember with army to flush C1","combine guard with army to flush N1")){
   var g=ready();int count=g.events().size();assertFalse(g.command("player",command).success(),command);
   assertEquals(count,g.events().size());assertEquals(1,g.seat("player").wealth);assertTrue(g.seat("player").hand.containsKey("ember"));assertFalse(g.isBurning("N1"));yes(g,"player",combine());
  }
 }
 @Test void distanceSuppliesAndManaAreValidatedBeforeIgnition(){
  var g=ready();yes(g,"player","move army S1");assertFalse(g.command("player",combine()).success());assertFalse(g.isBurning("N1"));
  g=ready();g.seat("player").wealth=0;assertFalse(g.command("player",combine()).success());assertTrue(g.seat("player").hand.containsKey("ember"));
  g=ready();g.seat("player").hand.put("ember",fire("ember",true));g.seat("player").mana=1;assertFalse(g.command("player",combine()).success());assertEquals(1,g.seat("player").mana);assertEquals(1,g.seat("player").wealth);
  g.seat("player").mana=2;yes(g,"player",combine());assertEquals(0,g.seat("player").mana);
 }
 @Test void fireSourceRangeAndDistinctCardsStillMatter(){
  var g=ready();g.seat("player").hand.put("local",new RndMatchEngine.Card("local","Local flame","Event",Set.of("IGNITE"),0,Set.of("FIRE")));
  assertFalse(g.command("player","combine local with army to flush N1").success());assertFalse(g.isBurning("N1"));
  g.seat("player").board.put("army",new RndMatchEngine.Card("army","Living flame","Military",Set.of("IGNITE","ATTACK"),5,Set.of("FIRE")));
  assertFalse(g.command("player","combine army with army to flush N1").success());assertEquals(1,g.seat("player").wealth);
 }
 @Test void alreadySpentActionsAndExistingFirePreventDuplicateScheduling(){
  var g=ready();yes(g,"player","draw");assertFalse(g.command("player",combine()).success());assertFalse(g.isBurning("N1"));
  g=ready();yes(g,"player",combine());g.seat("player").hand.put("other",fire("other",false));assertFalse(g.command("player","combine other with army to flush N1").success());assertEquals(1,g.discardedCount());
 }
 @Test void protectedDefendersRejectWithoutMutationAndNewPeaceCancelsPressure(){
  var g=ready();yes(g,"player","offer asha peace 3");yes(g,"player","pass");yes(g,"asha","accept D1");others(g);
  int before=g.events().size();assertFalse(g.command("player",combine()).success());assertEquals(before,g.events().size());assertFalse(g.isBurning("N1"));
  g=ready();yes(g,"player",combine());yes(g,"player","offer asha peace 3");yes(g,"player","pass");yes(g,"asha","accept D1");others(g);
  assertEquals("N1",g.cardLocation("guard").orElseThrow());assertTrue(g.command("player","interactions").message().contains("ceasefire now protects"));
 }
 @Test void immuneOnlyOrEmptyForestDoesNotSpendAnOrder(){
  var g=ready();g.seat("asha").board.put("guard",troop("guard",true));assertFalse(g.command("player",combine()).success());
  g.seat("asha").board.remove("guard");assertFalse(g.command("player",combine()).success());assertEquals(1,g.seat("player").wealth);assertTrue(g.seat("player").hand.containsKey("ember"));
 }
 @Test void lateRoundOrderWaitsForTheActualNextDefenderTurn(){
  var g=RndMatchEngine.startQuick(42);yes(g,"player","pass");g.seat("asha").hand.put("guard",troop("guard",false));yes(g,"asha","deploy guard");yes(g,"asha","pass");yes(g,"brian","pass");
  g.seat("mira").hand.put("ember",fire("ember",false));g.seat("mira").hand.put("army",troop("army",false));yes(g,"mira","deploy army");yes(g,"mira","move army N2");yes(g,"mira","pass");
  for(String id:List.of("player","asha","brian"))yes(g,id,"pass");yes(g,"mira",combine());yes(g,"mira","pass");
  assertEquals(2,g.round());assertEquals("N1",g.cardLocation("guard").orElseThrow());
  for(String id:List.of("player","asha","brian","mira"))yes(g,id,"pass");assertEquals("C1",g.cardLocation("guard").orElseThrow());
 }
 @Test void outOfTurnAndUnknownActorsCannotCoordinate(){
  var g=ready();assertFalse(g.command("asha",combine()).success());assertFalse(g.command("nobody",combine()).success());assertFalse(g.isBurning("N1"));
 }
}
