package com.wilddeck.app;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class KingdomReplayHarnessTest {
 @Test void reproducesTreasuryAndStockThroughRealTradeAndMineTransactions(){
  KingdomReplayHarness run=new KingdomReplayHarness();
  run.validate();
  var p=run.engine().kingdom("player");
  assertEquals(18,run.engine().cycle());
  assertEquals(21.4,p.gold,0.0001);
  assertEquals(2,p.wealth);assertEquals(8,p.mana);
  assertEquals(4,p.stock("Copper"));assertEquals(5,p.stock("Silver"));
  assertEquals(1,p.stock("Magic Crystal"));assertEquals(6,p.stock("Iron"));
  assertEquals(0,p.stock("Mithril"));
 }
 @Test void refusesFreeMaterialsAndPrematureMining(){
  var e=new KingdomRulesEngine();
  e.setCycle(17);e.checkpoint("player",5,4,1);
  assertFalse(e.develop("player","Mithril").success());
  assertTrue(e.discover("player","Mithril").success());
  assertTrue(e.develop("player","Mithril").success());
  assertEquals(0,e.kingdom("player").stock("Mithril"));
  e.advanceCycle();assertEquals(1,e.kingdom("player").stock("Mithril"));
  assertFalse(e.craft("player","Blacksmith","Sword",Map.of("Iron",2)).success());
 }
 @Test void tradingConservesGoldAndMaterials(){
  var e=new KingdomRulesEngine();e.checkpoint("player",10,2,2);
  e.checkpoint("brian",8,2,1);e.checkpointStock("player","Copper",2);
  double total=e.kingdom("player").gold+e.kingdom("brian").gold;
  assertFalse(e.trade("brian","player","Copper",4,2).success());
  assertEquals(2,e.kingdom("player").stock("Copper"));
  assertTrue(e.trade("brian","player","Copper",2,2).success());
  assertEquals(0,e.kingdom("player").stock("Copper"));
  assertEquals(2,e.kingdom("brian").stock("Copper"));
  assertEquals(total,e.kingdom("player").gold+e.kingdom("brian").gold,0.00001);
 }
 @Test void treatyBlocksAttackUntilExplicitWithdrawal(){
  var e=new KingdomRulesEngine();e.setCycle(16);e.treaty(Set.of("player","asha","brian"),25);
  e.giveCard("player","Hydra");assertTrue(e.deploy("player","Hydra").success());
  assertFalse(e.attack("player","brian","Hydra").success());
  assertTrue(e.withdraw("player").success());
  assertTrue(e.attack("player","brian","Hydra").message().contains("reaction"));
 }
}
