package com.wilddeck.app;

import java.util.*;

/**
 * Explicitly scripted three-kingdom scenario from the conversational playtest.
 * This is a deterministic scenario referee, NOT a random deck or authoritative
 * replacement for the general-purpose Java rules engine.
 */
public final class ThreeKingdomScenario {
    public record Card(String name,String kind,int strength,String owner,boolean deployed) {}
    private final Map<String,Kingdom> kingdoms=new LinkedHashMap<>();
    private final List<String> history=new ArrayList<>();
    private final List<Card> catalog=new ArrayList<>();
    private final Map<String,Integer> mineYield=new LinkedHashMap<>();
    private int cycle=18, peaceEndsAt=25, drawIndex=0;
    private String turn="player";
    private boolean finished=false;

    private static final class Kingdom {
        final String name;
        double gold;
        int wealth,mana;
        final List<String> hand=new ArrayList<>(), deployed=new ArrayList<>();
        final Map<String,Integer> inventory=new LinkedHashMap<>();
        final Map<String,String> orders=new LinkedHashMap<>();
        Kingdom(String name,double gold,int wealth,int mana){
            this.name=name;this.gold=gold;this.wealth=wealth;this.mana=mana;
        }
    }
    public ThreeKingdomScenario(){
        Kingdom p=new Kingdom("Your Kingdom",21.4,2,8);
        kingdoms.put("player",p);
        kingdoms.put("asha",new Kingdom("Asha's Kingdom",8,3,4));
        kingdoms.put("brian",new Kingdom("Brian's Kingdom",7,3,3));
        // Asha/Brian balances are placeholders rather than reconstructed facts.
        Collections.addAll(p.hand,"Black Plague","Tunnel Map","Mirror","Assassin",
                "Mirror of Revelation","War Banner","Ancient Titan");
        Collections.addAll(p.deployed,"Gold Mine","Hydra","Militia","Soulkeeper Necromancer",
                "Undead Cavalry","Mana Shrine","Stormcaller","Blacksmith","Beastmaster",
                "Arcane Militia","Royal Trade Caravan","Treasure Hunter","Enchanter",
                "Master Armorer","Geologist","Dire Wolf","Stoneburrower","Griffin","River Serpent");
        p.inventory.putAll(Map.of("Copper",4,"Silver",3,"Magic Crystal",0,
                "Iron",6,"Gemstone",2,"Sapphire",1,"Mithril",0));
        p.orders.put("Beastmaster","Explore new lands; discover creatures and minerals");
        p.orders.put("Treasure Hunter","Search ruins and hidden vaults");
        p.orders.put("Blacksmith","Hydra plating, then Arcane Militia equipment");
        p.orders.put("Geologist","Survey mineral deposits");
        mineYield.putAll(Map.of("Copper",2,"Silver",2,"Magic Crystal",1,
                "Gemstone",1,"Sapphire",1,"Mithril",1));
        Collections.addAll(kingdoms.get("asha").deployed,"Royal Guards","Watchtower","Field Medic",
                "Longbowmen","Stone Keep","Griffin Riders","Foundry","Engineer","Gold Mine",
                "Silver Mine","Royal Standard","Eagle Eye","Diplomat","Merchant Guild",
                "Merchant Fleet","Royal Cartographer");
        Collections.addAll(kingdoms.get("brian").deployed,"Iron Barricade","Mercenaries",
                "Siege Walker","Watchtower","Siege Engineer","Shadow Ranger","Appraiser",
                "Grand Alchemist","Clockwork Golem");
        seedCards();
        history.add("Scenario restored at Cycle 18 from chat state; 21.4 Gold, 2 Wealth, 8 Mana.");
        history.add("Three-way Trade Accord applies through Cycle 25, unless someone breaks it.");
        history.add("Asha operates a merchant fleet; Brian operates an alchemy laboratory.");
        history.add("Mithril mine established in Cycle 18: extraction begins Cycle 19.");
    }
    private void seedCards(){
        String[][] rows={
            {"Ancient Titan","Creature","12"},{"Hydra","Creature","8"},
            {"Militia","Military","4"},{"Undead Cavalry","Military","6"},
            {"Arcane Militia","Military","5"},{"Dire Wolf","Creature","4"},
            {"Stoneburrower","Creature","2"},{"Griffin","Creature","7"},
            {"River Serpent","Creature","9"},{"Black Plague","Disaster","0"},
            {"War Banner","Support","0"},{"Mirror","Artifact","0"},
            {"Mirror of Revelation","Artifact","0"},{"Assassin","Specialist","3"},
            {"Tunnel Map","Tool","0"},{"Treasure Hunter","Specialist","0"},
            {"Beastmaster","Specialist","0"},{"Blacksmith","Specialist","0"},
            {"Soulkeeper Necromancer","Specialist","0"},{"Stormcaller","Mage","0"},
            {"Enchanter","Specialist","0"},{"Master Armorer","Specialist","0"},
            {"Geologist","Specialist","0"},{"Clockwork Golem","Construct","8"},
            {"Dragon","Creature","13"},{"Volcano","Disaster","0"},
            {"Trickster","Wildcard","0"},{"Frost Giant","Creature","10"},
            {"Shadow Cloak","Equipment","0"},{"Mana Shrine","Structure","0"},
            {"Gold Mine","Structure","0"},{"Crystal Staff","Equipment","0"},
            {"Royal Trade Caravan","Trade","0"},{"Merchant Fleet","Trade","0"}};
        for(String[] row:rows) catalog.add(new Card(row[0],row[1],Integer.parseInt(row[2]),"",false));
    }
    private Kingdom player(){return kingdoms.get("player");}
    public int cycle(){return cycle;}
    public boolean isFinished(){return finished;}
    public List<String> history(){return List.copyOf(history);}
    public String command(String raw){
        String input=raw.trim(),lower=input.toLowerCase(Locale.ROOT);
        if(input.isEmpty()) return "Enter an order.";
        if(lower.equals("scenario")||lower.equals("dashboard")||lower.equals("status"))
            return summary();
        if(lower.equals("hand")) return "Hand: "+player().hand;
        if(lower.equals("cards")||lower.equals("catalog")) return catalog.toString();
        if(lower.equals("end")||lower.equals("pass")||lower.equals("next round")) return nextRound();
        if(lower.equals("draw")||lower.equals("draw a card")){
            // Provisional deterministic mixed-card sequence, explicitly not random.
            String[] sequence={"Frost Giant","Shadow Cloak","Dragon","Trickster","Crystal Staff","Volcano"};
            String card=sequence[drawIndex++%sequence.length];
            player().hand.add(card);history.add("You drew "+card+".");
            return "You drew "+card+". Provisional deterministic draw, not randomized yet.";
        }
        if(lower.startsWith("deploy ")||lower.startsWith("play ")){
            String wanted=input.substring(input.indexOf(' ')+1).trim();
            Optional<String> card=player().hand.stream().filter(c->c.equalsIgnoreCase(wanted)).findFirst();
            if(card.isEmpty()) return "That card is not in your hand.";
            String name=card.get();
            player().hand.remove(name);player().deployed.add(name);
            history.add("You deployed "+name+" for free.");
            return "Deployed "+name+" for free.";
        }
        if(lower.startsWith("assign ")||lower.startsWith("order ")){
            int index=lower.indexOf(" to ");
            if(index<0) return "Use: assign <deployed card> to <order>.";
            String name=input.substring(input.indexOf(' ')+1,index).trim(),task=input.substring(index+4).trim();
            if(player().deployed.stream().noneMatch(c->c.equalsIgnoreCase(name)))return "Unit not deployed.";
            player().orders.put(name,task);
            history.add(name+" assigned: "+task);
            return "Standing order accepted for "+name+": "+task;
        }
        if(lower.equals("explore")||lower.equals("continue exploration"))
            return "Operation Golden Frontier active. Exploration resolves on world-cycle advancement.";
        if(lower.startsWith("inspect ")) return inspect(input.substring(8).trim());
        if(lower.equals("break treaty")||lower.equals("end treaty")){
            peaceEndsAt=cycle-1;
            history.add("You publicly withdrew from the Three-Kingdom Accord in Cycle "+cycle+".");
            return "Treaty terminated by your kingdom. Asha and Brian may respond.";
        }
        if(lower.startsWith("attack ")){
            if(cycle<=peaceEndsAt)return "A peace treaty currently blocks war. 'break treaty' first.";
            history.add("Your attack order recorded: "+input+". Combat resolution still requires the warfare integration.");
            return "Attack declared, but this scenario cannot yet resolve combat. No damage applied.";
        }
        if(lower.startsWith("hire ")){
            String[] part=input.substring(5).split(" for ");
            if(part.length!=2)return "Use: hire <specialist> for <gold>.";
            int price;
            try{price=Integer.parseInt(part[1].replaceAll("[^0-9]",""));}
            catch(NumberFormatException ex){return "Invalid Gold amount.";}
            if(price<=0||player().gold<price)return "Invalid price or insufficient Gold.";
            player().gold-=price;player().deployed.add(part[0]);
            history.add("Hired "+part[0]+" for "+price+" Gold.");
            return "Hired "+part[0]+" for "+price+" Gold.";
        }
        if(lower.startsWith("craft ")){
            return "Crafting requested: "+input.substring(6)+". Recipe/material validation is not yet implemented; no resources consumed.";
        }
        if(lower.startsWith("trade ")||lower.startsWith("negotiate ")||lower.startsWith("visit ")){
            history.add("Proposed: "+input+" (pending the other ruler's decision).");
            return "Proposal recorded. No agreement, passage, money or resources transferred without a resolver.";
        }
        return "Unknown order. Use hand, draw, deploy, assign, explore, inspect, trade, visit, break treaty, or next round.";
    }
    private String inspect(String input){
        for(Card card:catalog)if(card.name().equalsIgnoreCase(input)){
            return card.name()+" | "+card.kind()+" | STR "+card.strength()+
                " | "+(player().hand.contains(card.name())?"in hand":"catalogued");
        }
        return "No card found.";
    }
    private String nextRound(){
        if(finished)return "Scenario finished.";
        // Opponents receive actual card assignments; no instant conjured production.
        String[] aDraw={"Trickster","Frost Giant","Shadow Cloak"};
        String[] bDraw={"Volcano","Dragon","Crystal Staff"};
        String a=aDraw[(cycle-18)%aDraw.length],b=bDraw[(cycle-18)%bDraw.length];
        kingdoms.get("asha").hand.add(a);kingdoms.get("brian").hand.add(b);
        history.add("Asha drew "+a+" and kept it in hand.");
        history.add("Brian drew "+b+" and kept it in hand.");
        cycle++;
        player().gold+=1;
        player().wealth+=4;
        player().mana+=1;
        for(var entry:mineYield.entrySet()) player().inventory.merge(entry.getKey(),entry.getValue(),Integer::sum);
        history.add("Cycle "+cycle+": Gold Mine +1 Gold, Wealth +4, Mana Shrine +1 Mana; seven mines extracted stock.");
        // No scripted enemy hand knowledge is shown to human UI, only public action logs.
        return "Cycle "+cycle+" began. Production completed; Asha and Brian have drawn private cards.";
    }
    public String summary(){
        return "Cycle "+cycle+" | Gold "+String.format(Locale.ROOT,"%.1f",player().gold)+
            " | Wealth "+player().wealth+" | Mana "+player().mana+
            "\nInventory "+player().inventory+"\nHand "+player().hand+
            "\nDeployed "+player().deployed+"\nOrders "+player().orders+
            "\nTrade Accord "+(cycle<=peaceEndsAt?"active through Cycle "+peaceEndsAt:"ended")+
            "\nAsha public kingdom "+kingdoms.get("asha").deployed+
            "\nBrian public kingdom "+kingdoms.get("brian").deployed;
    }
    public String visibleHistory(){
        int from=Math.max(0,history.size()-9);
        return String.join("\n",history.subList(from,history.size()));
    }
}
