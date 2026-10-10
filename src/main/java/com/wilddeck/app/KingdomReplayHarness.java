package com.wilddeck.app;
import java.util.*;
/** Executes verified actions through KingdomRulesEngine, never directly credits a trade. */
public final class KingdomReplayHarness {
 private final KingdomRulesEngine engine=new KingdomRulesEngine();
 private final List<String> gaps=new ArrayList<>();
 private void must(KingdomRulesEngine.Result r){if(!r.success())throw new IllegalStateException(r.message());}
 public KingdomReplayHarness(){
  // Initial cards and unplaced early-round actions were preserved, but not their exact cycle.
  for(String name:List.of("Gold Mine","Hydra","Militia","Necromancer","Soul Lantern","Mana Shrine",
      "Blacksmith","Beastmaster","Royal Trade Caravan","Stormcaller","Arcane Militia",
      "Treasure Hunter"))engine.giveCard("player",name);
  for(String name:List.of("Gold Mine","Hydra","Militia","Necromancer","Soul Lantern","Mana Shrine",
      "Blacksmith","Beastmaster","Royal Trade Caravan","Stormcaller","Arcane Militia","Treasure Hunter"))
      must(engine.deploy("player",name));
  // Late-cycle board cards derive from earlier combat/recruitment: these events currently
  // require a historical fixture because the exact prior turns are unavailable.
  engine.giveCard("player","Undead Cavalry");must(engine.deploy("player","Undead Cavalry"));
  for(String resource:List.of("Copper","Magic Crystal","Silver","Gemstone","Sapphire","Mithril"))
      must(engine.discover("player",resource));
  for(String creature:List.of("Dire Wolf","Stoneburrower","Griffin","River Serpent")){
      must(engine.discover("player",creature));must(engine.tame("player",creature,
          Map.of("Dire Wolf",4,"Stoneburrower",2,"Griffin",7,"River Serpent",9).get(creature),true));
  }
  must(engine.order("player","Beastmaster","Explore mountains, forests, caves, waterways and tame beasts"));
  must(engine.order("player","Blacksmith","Forge Hydra plating and Arcane Militia equipment"));
  must(engine.order("player","Treasure Hunter","Search ruins and treasure vaults"));
  // Checkpoint reconciling unavailable Cycles 1–16 transaction chronology.
  engine.setCycle(17);
  engine.checkpoint("player",12,2,7);
  for(String id:List.of("Copper","Magic Crystal","Silver","Gemstone","Sapphire")){
      int yield=Map.of("Copper",2,"Magic Crystal",1,"Silver",2,"Gemstone",1,"Sapphire",1).get(id);
      engine.kingdom("player").mines.put(id,new KingdomRulesEngine.Mine(id,yield,18));
  }
  for(var item:Map.of("Copper",4,"Magic Crystal",1,"Silver",4,"Gemstone",1,"Sapphire",0,"Iron",6).entrySet())
      engine.checkpointStock("player",item.getKey(),item.getValue());
  engine.kingdom("brian").gold=30;engine.kingdom("asha").gold=30;
  // NPC balance is only test-fixture liquidity, NOT claimed historical cash.
  engine.treaty(Set.of("player","asha","brian"),25);
  gaps.add("Exact chronological commands for Cycles 1-16 were not preserved; replay begins with documented unplaced actions then the Cycle 17 checkpoint.");
  gaps.add("Asha and Brian have test-fixture balances because their actual treasuries are unknown.");
  gaps.add("Combat, spell outcomes, forge STR upgrades and capture success still require generic resolvers.");
  // Cycle 18: production, Mithril development and actual transfers.
  engine.advanceCycle();
  must(engine.develop("player","Mithril"));
  must(engine.trade("brian","player","Copper",2,2));
  must(engine.trade("brian","player","Magic Crystal",1,5));
  must(engine.caravan("brian","player",1));
  must(engine.trade("asha","player","Silver",1,2.4));
  engine.kingdom("player").gold+=5; // reported vault discovery, not ordinary generated income
  must(engine.hire("player","Enchanter",3));
  must(engine.hire("player","Master Armorer",2));
  must(engine.hire("player","Geologist",2));
  engine.giveCard("player","Ancient Titan");
  validate();
 }
 public void validate(){
  var p=engine.kingdom("player");
  if(engine.cycle()!=18 || Math.abs(p.gold-21.4)>0.00001||p.wealth!=2||p.mana!=8)
      throw new IllegalStateException("R18 treasury mismatch "+p.gold+"/"+p.wealth+"/"+p.mana);
  if(p.stock("Mithril")!=0||p.stock("Iron")!=6||p.stock("Silver")!=5||p.stock("Copper")!=4)
      throw new IllegalStateException("material replay mismatch "+p.goods);
  if(!p.hand.containsKey("Ancient Titan")||!p.board.containsKey("River Serpent"))
      throw new IllegalStateException("historical card mismatch");
 }
 public KingdomRulesEngine engine(){return engine;}
 public List<String> gaps(){return List.copyOf(gaps);}
 public String report(){
  StringBuilder b=new StringBuilder("Verified actions through Java rules, reconstructed to Cycle 18\n");
  for(String event:engine.history())b.append(event).append("\n");
  var p=engine.kingdom("player");
  b.append("Gold ").append(p.gold).append(" Wealth ").append(p.wealth).append(" Mana ").append(p.mana)
   .append("\nInventory ").append(p.goods).append("\nHand ").append(p.hand.keySet())
   .append("\nBoard ").append(p.board.keySet()).append("\nGaps:\n");
  for(String gap:gaps)b.append("- ").append(gap).append("\n");
  return b.toString();
 }
}
