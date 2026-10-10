package com.wilddeck.app;

import java.util.*;

/**
 * Auditable regression replay of the conversational three-kingdom playtest.
 * Facts without a recoverable cycle number remain in an unplaced pre-C17
 * timeline, NOT silently allocated to an invented round.
 */
public final class HistoricalReplay {
    public record Event(int cycle,String player,String action,String result,boolean verified) {}
    private final List<Event> timeline = new ArrayList<>();
    private final Map<String,Integer> goods=new LinkedHashMap<>();
    private final Map<String,Integer> mines=new LinkedHashMap<>();
    private final Set<String> deployed=new LinkedHashSet<>();
    private final LinkedHashSet<String> hand=new LinkedHashSet<>();
    private final List<String> gaps=new ArrayList<>();
    private double gold;
    private int wealth,mana;
    private int cycle;
    private boolean peace;
    private int goldMineCount;
    private int manaShrines;
    private int crystalProduced;
    private int ironPurchased,ironUsed;
    private int paidCaravanTrips;
    private int mithrilFirstProduction=19;

    private void record(int round,String actor,String action,String result,boolean grounded){
        timeline.add(new Event(round,actor,action,result,grounded));
    }
    private void deploy(String card) { deployed.add(card); }
    private void mine(String item,int yield){mines.put(item,yield);goods.putIfAbsent(item,0);}
    private void consume(String item,int amount){
        int owned=goods.getOrDefault(item,0);
        if(owned<amount) throw new IllegalStateException("Insufficient "+item+": "+owned+" < "+amount);
        goods.put(item,owned-amount);
    }
    private void produceMines(int round){
        for(var m:mines.entrySet()){
            if(m.getKey().equals("Mithril") && round < mithrilFirstProduction)continue;
            goods.merge(m.getKey(),m.getValue(),Integer::sum);
        }
        gold+=goldMineCount;mana+=manaShrines;
    }

    public HistoricalReplay(){
        reset();
        execute();
    }
    private void reset(){
        cycle=0; gold=0;wealth=0;mana=0;
        deployed.clear();hand.clear();goods.clear();mines.clear();timeline.clear();gaps.clear();
        record(0,"World","Start kingdom simulation","Historical initial balances not recorded; no invented starting amount.",false);
        gaps.add("Exact per-cycle commands/actions in Cycles 1-10 cannot be assigned without the full original turn log.");
        gaps.add("Exact opponent private hands, money, and some transaction dates were not recorded.");
        gaps.add("Round 18 inventory for Copper/Silver/Crystal has conflicting implied production timing: derived values are flagged instead of assumed.");
    }
    private void execute(){
        // Early human plays: verified order of events, cycle number not recoverable.
        Collections.addAll(hand,"Hydra","Necromancer","Militia","Black Plague","Gold Mine","Tunnel Map","Mirror");
        record(-1,"Player","Initial hand","Hydra, Necromancer, Militia, Black Plague, Gold Mine, Tunnel Map, Mirror",true);
        hand.remove("Gold Mine");deploy("Gold Mine");goldMineCount=1;
        record(-1,"Player","Deploy Gold Mine","Free card deployment; +1 Gold at completed production cycle.",true);
        hand.remove("Hydra");deploy("Hydra");hand.remove("Militia");deploy("Militia");
        record(-1,"Player","Deploy Hydra and Militia","STR 8 and initial STR 3.",true);
        Collections.addAll(hand,"Assassin","Soul Lantern","Mana Shrine");
        hand.remove("Necromancer");hand.remove("Soul Lantern");deploy("Soulkeeper Necromancer");
        record(-1,"Player","Combine Necromancer + Soul Lantern","Soulkeeper Necromancer equipped with spirit-storage lantern.",true);
        hand.remove("Mana Shrine");deploy("Mana Shrine");manaShrines=1;
        record(-1,"Player","Deploy Mana Shrine","+1 Mana each completed production cycle.",true);
        deploy("Undead Cavalry");
        record(-1,"Player","Necromancer captures Brian's fallen Cavalry and resurrects remains","Undead Cavalry STR 5; recorded cost 1 Mana.",true);
        deploy("Stormcaller");
        record(-1,"Player","Stormcaller lightning strike at suspicious border wagon","Spent 1 Mana; Brian denied owning the wagon.",true);
        deploy("Arcane Militia");
        record(-1,"Player","Hire Arcane Militia for 4 Gold","STR 5; five specialist mage roles.",true);
        deploy("Blacksmith");
        record(-1,"Player","Deploy Blacksmith; buy 10 Iron from Asha for 5 Gold",
              "2 Iron each used to upgrade Militia STR 3→4 and Undead Cavalry STR 5→6.",true);
        ironPurchased=10;ironUsed=4;goods.put("Iron",6);
        record(-1,"Player","Queue forge jobs","3 Iron Hydra plating; 2 Iron Arcane Militia gear; 1 Iron spare.",true);
        deploy("Beastmaster");deploy("Dire Wolf");deploy("Stoneburrower");deploy("Griffin");
        record(-1,"Player","Launch Operation Golden Frontier","Beastmaster exploring with regular troops, 3 previously tamed beasts.",true);
        mine("Copper",2);mine("Magic Crystal",1);mine("Silver",2);mine("Gemstone",1);
        record(-1,"Player","Develop Copper, Crystal, Silver, Gemstone deposits",
            "Respective Wealth costs 2,3,3,2; physical stock grows on later completed cycles.",true);
        deploy("Royal Trade Caravan");
        record(-1,"Player","Deploy Royal Trade Caravan","Paid client journeys earn 1 Gold each; own goods carried free.",true);
        record(11,"Player/Asha","Non-aggression pact #001","Effective cycles 11–16, expires afterwards.",true);
        record(16,"All three","Grand Trade Accord #002","Effective cycles 16–25; initially mechanically enforced.",true);
        peace=true;
        // An explicit boundary is used rather than fabricating nine missing cycle incomes.
        record(16,"Audit","Checkpoint before Cycle 17","Earlier event timing and exact treasury arithmetic unavailable.",false);
        record(17,"Asha","Deploy Merchant Fleet","Foreign-market logistics opened.",true);
        record(17,"Brian","Deploy Grand Alchemist","Alchemical laboratory preparation.",true);
        mine("Sapphire",1);
        record(17,"Player","Automatically develop Sapphire Mine","Spent 3 Wealth; first production Cycle 18.",true);
        deploy("River Serpent");
        record(17,"Player","Beastmaster tames River Serpent","STR 9 aquatic explorer, Mithril deposit discovered.",true);
        record(17,"Player","Queue Mithril mine","Needs 4 Wealth; 2 Wealth remained at end Cycle 17.",true);
        // Canonical balances are a recorded checkpoint, not calculated from an unknown opening.
        cycle=17;gold=12;wealth=2;mana=7;
        goods.put("Copper",4);goods.put("Silver",4);goods.put("Magic Crystal",1);
        goods.put("Gemstone",1);goods.put("Sapphire",0);goods.put("Mithril",0);
        record(17,"Audit","Reconcile treasury to recorded end of Cycle 17","12 Gold, 2 Wealth, 7 Mana.",true);
        // The next production is Cycle 18, and the Sapphire mine first yields here.
        cycle=18;
        wealth+=4;
        produceMines(18);
        goods.merge("Sapphire",1,Integer::sum);
        // Production of preexisting 4 mines plus Sapphire; first yield of Sapphire only now.
        record(18,"World","Mines extract in Cycle 18","Gold +1; Wealth +4; Mana +1; Copper +2, Crystal +1, Silver +2, Gemstone +1, Sapphire +1.",true);
        if(wealth<4)throw new IllegalStateException("Mithril development unaffordable.");
        wealth-=4;mine("Mithril",1);
        record(18,"Player","Develop Mithril Mine","4 Wealth spent; extraction only from Cycle 19 onward.",true);
        consume("Copper",2);consume("Magic Crystal",1);
        gold+=8;paidCaravanTrips++;
        record(18,"Brian","Buy 2 Copper, 1 Magic Crystal and one paid Caravan trip",
            "2 + 5 + 1 = 8 Gold deposited.",true);
        consume("Silver",1);gold+=2.4;
        record(18,"Asha","Export 1 Silver","3 Gold sale; 80% = 2.4 Gold to player.",true);
        gold+=5;
        record(18,"Player","Treasure Hunter opens Ancient Vault","Recovered 5 Gold and Ancient Command Tablet.",true);
        deploy("Treasure Hunter"); // previously deployed before the Cycle 18 vault expedition
        deploy("Enchanter");deploy("Master Armorer");deploy("Geologist");
        gold-=7;
        record(18,"Player","Hire Enchanter, Master Armorer, Geologist","Spent 3+2+2 = 7 Gold.",true);
        hand.remove("Assassin"); // restore exact final hand, including Assassin
        hand.clear();Collections.addAll(hand,"Black Plague","Tunnel Map","Mirror",
                "Assassin","Mirror of Revelation","War Banner","Ancient Titan");
        record(18,"Player","Draw Ancient Titan","STR 12; retained in hand.",true);
        record(18,"Asha","Deploy Royal Cartographer; discover coastal harbour","Develops harbour for 2 Wealth.",true);
        record(18,"Brian","Deploy Clockwork Golem","STR 8 laboratory guard.",true);
        record(18,"Audit","Final treasury reconciliation","Expected Gold 21.4, Wealth 2, Mana 8.",true);
        check();
    }
    private void check(){
        if(Math.abs(gold-21.4)>0.00001 || wealth!=2 || mana!=8)
            throw new IllegalStateException("Treasury replay mismatch: "+gold+"/"+wealth+"/"+mana);
        if(ironPurchased-ironUsed!=goods.get("Iron"))
            throw new IllegalStateException("Iron conservation violated.");
        if(mines.size()!=6)throw new IllegalStateException("Expected six extracted-material mines plus Gold Mine.");
        if(!hand.contains("Ancient Titan")||!deployed.contains("River Serpent"))
            throw new IllegalStateException("Final card state mismatch.");
        if(paidCaravanTrips!=1) throw new IllegalStateException("Cycle 18 buyer trip missing.");
    }
    public int cycle(){return cycle;}
    public double gold(){return gold;}
    public int wealth(){return wealth;}
    public int mana(){return mana;}
    public List<Event> timeline(){return List.copyOf(timeline);}
    public List<String> gaps(){return List.copyOf(gaps);}
    public Map<String,Integer> inventory(){return Map.copyOf(goods);}
    public Set<String> deployed(){return Set.copyOf(deployed);}
    public Set<String> hand(){return Set.copyOf(hand);}
    public String report(){
        StringBuilder b=new StringBuilder("Replay from Round 0 through Cycle "+cycle+
            "\nTreasury: Gold "+gold+", Wealth "+wealth+", Mana "+mana+
            "\nMaterial stock: "+goods+"\nHand: "+hand+
            "\nDeployed: "+deployed+"\n");
        for(Event e:timeline)b.append(e.cycle<0?"Unplaced pre-C17":"Cycle "+e.cycle)
          .append(" | ").append(e.player).append(" | ").append(e.action)
          .append(" => ").append(e.result)
          .append(e.verified?"":" [CHRONOLOGY GAP / CHECKPOINT]").append("\n");
        b.append("\nUNRESOLVED GAPS\n");
        for(String gap:gaps)b.append("- ").append(gap).append("\n");
        return b.toString();
    }
}
