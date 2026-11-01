package com.wilddeck.app;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class RndCardActionsTest {
 private RndMatchEngine.Card unit(String id,int strength,boolean biological){return new RndMatchEngine.Card(id,id,"Military",Set.of("ATTACK","DEFEND","OCCUPY"),strength,biological?Set.of("BIOLOGICAL"):Set.of());}
 private RndMatchEngine.Card gear(String id,int armor,boolean fireproof){return new RndMatchEngine.Card(id,id,"Equipment",Set.of("ATTACH"),0,fireproof?Set.of("FIREPROOF"):Set.of(),armor==0?Map.of("ARMOR",0,"ATTACK_BONUS",1):Map.of("ARMOR",armor));}
 private void round(RndMatchEngine g){for(String id:List.of("player","asha","brian","mira"))assertTrue(g.command(id,"pass").success());}
 private RndMatchEngine armoredBattle(int armor){return armoredBattle(armor,6);}
 private RndMatchEngine armoredBattle(int armor,int defense){
  var g=RndMatchEngine.start(836);g.seat("player").hand.put("army",unit("army",5,true));
  g.command("player","deploy army");g.command("player","pass");g.seat("asha").hand.put("guard",unit("guard",defense,true));g.command("asha","deploy guard");
  for(String id:List.of("asha","brian","mira"))g.command(id,"pass");
  g.seat("player").hand.put("shield",gear("shield",armor,false));assertTrue(g.command("player","attach shield to army").success());
  g.command("player","move army C1");round(g);g.command("player","move army N1");return g;
 }
 @Test void quickModeIsSeededAndHasSupportedOpeningCards(){
  var a=RndMatchEngine.startQuick(42);var b=RndMatchEngine.startQuick(42);
  assertTrue(a.quickMatch());assertEquals(55,a.poolSize());assertEquals(27,a.deckSize());
  assertEquals(a.seat("player").hand.keySet(),b.seat("player").hand.keySet());
  assertFalse(a.cardsView("player").contains("effect not implemented"));
  assertEquals(200,RndMatchEngine.start(42).poolSize());
 }
 @Test void oneShotQuickCardsCannotBeWastedAsPermanentDeployment(){
  var g=RndMatchEngine.startQuick(42);var event=new RndMatchEngine.Card("event","Test Fire","Event",Set.of("IGNITE","WORLD_EFFECT"),0,Set.of("FIRE"));g.seat("player").hand.put("event",event);
  assertFalse(g.command("player","deploy event").success());assertTrue(g.seat("player").hand.containsKey("event"));assertTrue(g.command("player","use Test Fire on N1").success());
 }
 @Test void incomeIsDataDrivenAndSettlesOnceAtRoundBoundary(){
  var g=RndMatchEngine.start(836);g.seat("player").hand.put("producer",new RndMatchEngine.Card("producer","Generic Producer","Structure",Set.of("PRODUCE"),0,Set.of(),Map.of("GOLD_INCOME",2,"MANA_INCOME",1,"WEALTH_INCOME",1)));
  g.command("player","deploy producer");assertEquals(0,g.seat("player").gold);round(g);
  assertEquals(2,g.seat("player").gold);assertEquals(1,g.seat("player").mana);assertEquals(2,g.seat("player").wealth);
 }
 @Test void directEquipConsumesMainActionAndFollowsCarrier(){
  var g=RndMatchEngine.start(836);g.seat("player").hand.put("army",unit("army",5,true));g.command("player","deploy army");round(g);
  g.seat("player").hand.put("mail",gear("mail",1,true));assertTrue(g.command("player","attach mail to army").success());
  assertFalse(g.command("player","draw").success());assertTrue(g.command("player","move army C1").success());
  assertEquals("C1",g.cardLocation("mail").orElseThrow());assertEquals("army",g.attachedTo("mail").orElseThrow());
  assertFalse(g.command("player","attach mail to army").success());
 }
 @Test void equippedFireproofTraitAllowsBurningEntry(){
  var g=RndMatchEngine.start(836);g.seat("player").hand.put("army",unit("army",5,true));g.command("player","deploy army");round(g);
  g.seat("player").hand.put("mail",gear("mail",1,true));g.command("player","attach mail to army");g.command("player","move army C1");round(g);
  g.command("player","use Wildfire on N1");assertTrue(g.command("player","move army N1").success());assertEquals("N1",g.cardLocation("mail").orElseThrow());
 }
 @Test void armorPreventsSomeDamageAndSurvivingDefenderBlocksCapture(){
  var g=armoredBattle(2);g.command("player","attack asha");assertEquals(1,g.remainingStrength("army"));assertEquals(1,g.remainingStrength("guard"));
  assertTrue(g.attachedTo("shield").isPresent());assertFalse(g.command("player","conquer asha").success());
 }
 @Test void deathDropsEquipmentWithoutDanglingCarrier(){
  var g=armoredBattle(0);g.command("player","attack asha");assertEquals(0,g.remainingStrength("army"));assertTrue(g.attachedTo("shield").isEmpty());
  assertTrue(g.seat("player").board.containsKey("shield"));assertEquals("N1",g.cardLocation("shield").orElseThrow());assertDoesNotThrow(()->g.view("player"));
 }
 @Test void healerRequiresSuppliesRangeAndOncePerRound(){
  var g=armoredBattle(2);g.command("player","attack asha");
  var medic=new RndMatchEngine.Card("medic","Field Medic","Specialist",Set.of("HEAL","MOVE"),0,Set.of(),Map.of("HEAL",2));
  g.seat("player").hand.put("medic",medic);assertTrue(g.command("player","deploy medic").success());
  assertFalse(g.command("player","heal Field Medic to army").success());g.command("player","move medic C1");round(g);g.command("player","move medic N1");
  int wealth=g.seat("player").wealth;assertTrue(g.command("player","heal Field Medic to army").success());assertEquals(3,g.remainingStrength("army"));assertEquals(wealth-1,g.seat("player").wealth);
  assertFalse(g.command("player","heal Field Medic to army").success());assertEquals(wealth-1,g.seat("player").wealth);
  round(g);assertTrue(g.command("player","heal Field Medic to army").success());assertEquals(5,g.remainingStrength("army"));
 }
 @Test void healingRainHealsBothSidesAndConsumesCardManaAndMainAction(){
  var g=armoredBattle(2);g.command("player","attack asha");
  var rain=new RndMatchEngine.Card("rain","Restoring Rain","Spell",Set.of("HEAL_AREA","WORLD_EFFECT","MAGIC"),0,Set.of("WATER"),Map.of("HEAL_AREA",2));g.seat("player").hand.put("rain",rain);
  g.seat("player").mana=1;assertFalse(g.command("player","use Restoring Rain on N1").success());assertEquals(1,g.remainingStrength("army"));assertTrue(g.seat("player").hand.containsKey("rain"));
  g.seat("player").mana=2;assertTrue(g.command("player","use Restoring Rain on N1").success());assertEquals(3,g.remainingStrength("army"));assertEquals(3,g.remainingStrength("guard"));assertEquals(0,g.seat("player").mana);assertFalse(g.command("player","draw").success());
 }
 @Test void aDeadAttackingForceCannotCaptureAnAsset(){
  var g=armoredBattle(0,5);g.command("player","pass");g.command("asha","deploy Arcane Tower");
  for(String id:List.of("asha","brian","mira"))g.command(id,"pass");
  g.command("player","attack asha");assertEquals(0,g.remainingStrength("army"));assertTrue(g.seat("asha").board.containsKey("WD-073"));assertFalse(g.seat("player").board.containsKey("WD-073"));
 }
 @Test void healersCannotTreatConstructsOrEnemyPatients(){
  var g=armoredBattle(2);g.command("player","attack asha");
  g.seat("player").board.put("army",unit("army",5,false));
  var healer=new RndMatchEngine.Card("medic","Medic","Specialist",Set.of("HEAL","MOVE"),0,Set.of(),Map.of("HEAL",2));
  g.seat("player").hand.put("medic",healer);g.command("player","deploy medic");g.command("player","move medic C1");round(g);g.command("player","move medic N1");
  int wealth=g.seat("player").wealth;assertFalse(g.command("player","heal Medic to army").success());assertFalse(g.command("player","heal Medic to guard").success());assertEquals(wealth,g.seat("player").wealth);
 }
 @Test void goldPaymentIsFixedAndDoesNotSpendWealth(){
  var g=armoredBattle(2);g.command("player","attack asha");
  var healer=new RndMatchEngine.Card("medic","Medic","Specialist",Set.of("HEAL","MOVE"),0,Set.of(),Map.of("HEAL",2));
  g.seat("player").hand.put("medic",healer);g.command("player","deploy medic");g.command("player","move medic C1");round(g);g.command("player","move medic N1");
  g.seat("player").gold=1;int wealth=g.seat("player").wealth;assertFalse(g.command("player","heal Medic to army with gold").success());assertEquals(wealth,g.seat("player").wealth);
  g.seat("player").gold=2;assertTrue(g.command("player","heal Medic to army with gold").success());assertEquals(0,g.seat("player").gold);assertEquals(wealth,g.seat("player").wealth);assertEquals(3,g.remainingStrength("army"));
 }
 @Test void invalidAttachmentLeavesHandAndActionUnchanged(){
  var g=RndMatchEngine.start(836);g.seat("player").hand.put("mail",gear("mail",1,true));assertFalse(g.command("player","attach mail to enemy").success());
  assertTrue(g.seat("player").hand.containsKey("mail"));assertTrue(g.command("player","draw").success());
 }
}
