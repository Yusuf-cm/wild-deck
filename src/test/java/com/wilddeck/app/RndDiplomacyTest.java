package com.wilddeck.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class RndDiplomacyTest {
 private RndMatchEngine game(){return RndMatchEngine.startQuick(42);}
 private void yes(RndMatchEngine g,String actor,String command){var r=g.command(actor,command);assertTrue(r.success(),command+": "+r.message());}
 private void round(RndMatchEngine g){for(String id:List.of("player","asha","brian","mira"))if(!g.eliminated(id))yes(g,id,"pass");}
 private void peace(RndMatchEngine g,int duration){yes(g,"player","offer asha peace "+duration);yes(g,"player","pass");yes(g,"asha","accept D1");for(String id:List.of("asha","brian","mira"))yes(g,id,"pass");}
 private RndMatchEngine.Card army(){return new RndMatchEngine.Card("army","Army","Military",Set.of("ATTACK","DEFEND","OCCUPY"),20);}

 @Test void settlementIsAtomicConservesResourcesAndCannotReplay(){
  var g=game();g.seat("player").gold=4;g.seat("asha").wealth=3;
  yes(g,"player","offer asha give 4 gold for 2 wealth");
  assertEquals(4,g.seat("player").gold);assertEquals(3,g.seat("asha").wealth);
  assertFalse(g.command("asha","accept D1").success()); // not her turn
  yes(g,"player","pass");yes(g,"asha","accept d1");
  assertEquals(0,g.seat("player").gold);assertEquals(2,g.seat("player").wealth);
  assertEquals(4,g.seat("asha").gold);assertEquals(1,g.seat("asha").wealth);
  int events=g.events().size();assertFalse(g.command("asha","accept D1").success());assertEquals(events,g.events().size());
  yes(g,"asha","draw"); // settlement did not consume the main card action
 }
 @Test void staleFundsAndOverflowRejectBeforeEitherSidePays(){
  var g=game();g.seat("player").gold=4;g.seat("asha").mana=2;
  yes(g,"player","offer asha give 4 gold for 2 mana");g.seat("player").gold=3;yes(g,"player","pass");
  assertFalse(g.command("asha","accept D1").success());assertEquals(3,g.seat("player").gold);assertEquals(2,g.seat("asha").mana);
  assertTrue(g.command("asha","deals").message().contains("PENDING"));
  g.seat("player").gold=4;g.seat("asha").gold=Integer.MAX_VALUE;
  assertFalse(g.command("asha","accept D1").success());assertEquals(4,g.seat("player").gold);assertEquals(2,g.seat("asha").mana);
 }
 @Test void responderAlsoNeedsFundsAtAcceptance(){
  var g=game();g.seat("player").gold=2;
  yes(g,"player","offer asha give 2 gold for 1 mana");yes(g,"player","pass");
  assertFalse(g.command("asha","accept D1").success());assertEquals(2,g.seat("player").gold);assertEquals(0,g.seat("asha").gold);
 }
 @Test void cancellationRejectionAndRecipientAuthority(){
  var g=game();yes(g,"player","offer asha peace 3");
  assertFalse(g.command("player","accept D1").success());assertFalse(g.command("player","reject D1").success());
  yes(g,"player","cancel D1");yes(g,"player","offer asha peace 2");yes(g,"player","pass");
  assertFalse(g.command("asha","cancel D2").success());yes(g,"asha","reject D2");
  yes(g,"asha","pass");assertFalse(g.command("brian","accept D2").success());
 }
 @Test void expiredOfferCannotBeAcceptedOrMoveMoney(){
  var g=game();g.seat("player").gold=2;g.seat("asha").mana=1;
  yes(g,"player","offer asha give 2 gold for 1 mana");round(g);round(g);
  assertTrue(g.command("player","deals").message().contains("EXPIRED"));yes(g,"player","pass");
  assertFalse(g.command("asha","accept D1").success());assertEquals(2,g.seat("player").gold);assertEquals(1,g.seat("asha").mana);
 }
 @Test void malformedOffersAreNonMutatingAndDontAllocateIds(){
  var g=game();g.seat("player").gold=5;
  for(String cmd:List.of("offer asha","offer asha peace 0","offer asha peace 11","offer asha peace -2",
      "offer player peace 2","offer nobody peace 2","offer asha give 0 gold for 1 mana",
      "offer asha give 2 gold for 1 gold","offer asha give 6 gold for 1 mana",
      "offer asha give 999999999999999999999 gold for 1 mana","offer asha give 1 gold for 1 mana peace 0",
      "offer asha give 1 gold for 1 crystal","offer asha peace 3 trailing")){
   int count=g.events().size();assertFalse(g.command("player",cmd).success(),cmd);assertEquals(count,g.events().size(),cmd);assertEquals(5,g.seat("player").gold);
  }
  assertTrue(g.command("player","offer asha peace 3").message().contains("D1"));
 }
 @Test void pendingTermsAreNotExposedToUninvolvedViewers(){
  var g=game();g.seat("player").gold=17;yes(g,"player","offer asha give 17 gold for 3 mana");
  assertTrue(g.command("asha","deals").message().contains("17 GOLD"));
  assertFalse(g.command("brian","deals").message().contains("17 GOLD"));
  assertFalse(g.command("brian","events").message().contains("17 GOLD"));
  assertFalse(g.view("brian").contains("17 GOLD"));
 }
 @Test void duplicateOffersAndSpamAreBounded(){
  var g=game();yes(g,"player","offer asha peace 3");assertFalse(g.command("player","offer asha peace 2").success());
  yes(g,"player","cancel D1");yes(g,"player","offer asha peace 2");yes(g,"player","cancel D2");
  yes(g,"player","offer asha peace 1");yes(g,"player","cancel D3");assertFalse(g.command("player","offer brian peace 2").success());
  round(g);yes(g,"player","offer brian peace 2");
 }
 @Test void peaceBlocksEntryAndFireWithoutCostsUntilExplicitBetrayal(){
  var g=RndMatchEngine.start(836);g.seat("player").hand.put("army",army());
  yes(g,"player","deploy army");yes(g,"player","move army C1");peace(g,3);
  int count=g.events().size();assertFalse(g.command("player","move army N1").success());
  assertFalse(g.command("player","use Wildfire on N1").success());assertFalse(g.command("player","attack asha").success());
  assertFalse(g.command("player","conquer asha").success());assertEquals(count,g.events().size());
  assertTrue(g.seat("player").hand.containsKey("WD-171"));assertFalse(g.isBurning("N1"));
  yes(g,"player","betray asha");yes(g,"player","move army N1");yes(g,"player","attack asha");
  assertFalse(g.command("player","betray asha").success());
  assertTrue(g.command("player","deals").message().contains("{player=1}"));
 }
 @Test void peaceAlsoProtectsPartnersUnitsOutsideTheirCapital(){
  var g=RndMatchEngine.start(836);yes(g,"player","offer brian peace 3");yes(g,"player","pass");yes(g,"asha","pass");
  yes(g,"brian","accept D1");g.seat("brian").hand.put("army",army());yes(g,"brian","deploy army");yes(g,"brian","move army N1");
  for(String id:List.of("brian","mira"))yes(g,id,"pass");
  assertTrue(g.command("player","use Wildfire on N1").message().contains("ceasefire protects"));assertFalse(g.isBurning("N1"));
 }
 @Test void peaceExpiresNaturallyWithoutLostTrust(){
  var g=game();peace(g,1);assertTrue(g.events().stream().anyMatch(e->e.contains("ceasefire ended without a betrayal")));
  assertFalse(g.command("player","betray asha").success());assertTrue(g.command("player","deals").message().contains("Public broken promises: {}"));
  yes(g,"player","offer asha peace 2");
 }
 @Test void peaceCannotPaperOverAnInvasionOrOccupation(){
  var g=game();g.seat("player").hand.put("army",army());yes(g,"player","deploy army");yes(g,"player","move army C1");round(g);yes(g,"player","move army N1");
  assertFalse(g.command("player","offer asha peace 3").success());
  yes(g,"player","attack asha");yes(g,"player","conquer asha");
  assertFalse(g.command("player","offer asha peace 3").success());
 }
 @Test void proposalIsRevalidatedIfTroopsInvadeBeforeAcceptance(){
  var g=game();g.seat("player").hand.put("army",army());yes(g,"player","deploy army");yes(g,"player","move army C1");round(g);
  yes(g,"player","offer asha peace 3");yes(g,"player","move army N1");yes(g,"player","pass");
  assertFalse(g.command("asha","accept D1").success());assertTrue(g.command("asha","deals").message().contains("PENDING"));
 }
 @Test void paidPeaceSettlesAndBetrayalDoesNotRefund(){
  var g=game();g.seat("player").gold=4;g.seat("asha").mana=2;
  yes(g,"player","offer asha give 4 gold for 2 mana peace 3");yes(g,"player","pass");yes(g,"asha","accept D1");
  yes(g,"asha","betray player");assertEquals(4,g.seat("asha").gold);assertEquals(2,g.seat("player").mana);
  assertTrue(g.events().stream().anyMatch(e->e.contains("asha BETRAYED player")));
 }
 @Test void opponentsAcceptFairDealsAndRejectLosingTradesAndBrokenPromises(){
  var g=game();g.seat("player").gold=4;g.seat("asha").mana=2;
  yes(g,"player","offer asha give 4 gold for 2 mana peace 3");yes(g,"player","pass");
  assertTrue(g.takeOpponentTurns().stream().anyMatch(x->x.contains("D1 ACCEPTED")));assertEquals(2,g.seat("player").mana);
  yes(g,"player","betray asha");yes(g,"player","offer asha peace 3");yes(g,"player","pass");
  assertTrue(g.takeOpponentTurns().stream().anyMatch(x->x.contains("D2 REJECTED")));
  g.seat("player").gold=1;yes(g,"player","offer asha give 1 gold for 1 mana");yes(g,"player","pass");
  assertTrue(g.takeOpponentTurns().stream().anyMatch(x->x.contains("D3 REJECTED")));assertEquals(1,g.seat("player").gold);
 }
 @Test void opponentsRejectOffersTheyCannotFund(){
  var g=game();g.seat("player").gold=2;
  yes(g,"player","offer asha give 2 gold for 1 mana");yes(g,"player","pass");
  assertTrue(g.takeOpponentTurns().stream().anyMatch(x->x.contains("D1 REJECTED")));assertEquals(2,g.seat("player").gold);
 }
 @Test void eliminatedKingdomsCannotTradeAndOutstandingOffersExpire(){
  var g=game();g.seat("player").hand.put("army",army());yes(g,"player","deploy army");yes(g,"player","move army C1");round(g);
  yes(g,"player","offer asha peace 3");yes(g,"player","move army N1");yes(g,"player","attack asha");yes(g,"player","conquer asha");round(g);
  assertTrue(g.eliminated("asha"));assertTrue(g.command("player","deals").message().contains("EXPIRED"));
  assertFalse(g.command("player","offer asha peace 3").success());assertFalse(g.command("asha","accept D1").success());
 }
}
