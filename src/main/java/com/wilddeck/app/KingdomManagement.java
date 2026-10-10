package com.wilddeck.app;

import com.wilddeck.engine.*;
import java.util.*;

/**
 * The human-play management slice. This layer is deterministic and deliberately
 * separate from AI prompting: commands mutate a real ledger, never narrated state.
 */
public final class KingdomManagement {
    private static final String[] DISCOVERIES = {
        "Copper Vein", "Crystal Deposit", "Silver Deposit", "Gemstone Deposit",
        "Sapphire Deposit", "Iron Vein", "Mithril Deposit"
    };
    private static final int[] COSTS = {2,3,3,2,3,2,4};
    private static final int[] YIELDS = {2,1,2,1,1,2,1};
    private final Map<String,String> assignments = new LinkedHashMap<>();
    private final Map<String,Deposit> deposits = new LinkedHashMap<>();
    private final Map<String,Integer> stocks = new LinkedHashMap<>();
    private final List<String> journal = new ArrayList<>();
    private final Map<String,Integer> hires = new LinkedHashMap<>();
    private final Map<String,Integer> marketPrices = new LinkedHashMap<>();
    private int caravanTrips;
    private boolean caravanEnabled;
    private boolean autoMining;
    private boolean exploring;
    private int lastSurveyRound;
    private int exploredSites;

    private record Deposit(String name,int cost,int yield,boolean developed,int firstRound) {}

    public boolean command(String input,GameState state,String playerId) {
        String line = input.trim(), low = line.toLowerCase(Locale.ROOT);
        if (low.equals("kingdom") || low.equals("dashboard") || low.equals("inventory")
                || low.equals("discoveries") || low.equals("orders")) {
            System.out.println(dashboard(state,playerId));
            return true;
        }
        if (low.equals("open market") || low.equals("establish trade caravan")) {
            caravanEnabled=true;
            log("Royal Caravan active: 1 Gold charged to clients per completed transport trip.");
            return true;
        }
        if (low.startsWith("hire ")) {
            String rest=line.substring(5).trim();
            int at=rest.toLowerCase(Locale.ROOT).lastIndexOf(" for ");
            if (at<1) { System.out.println("Use: hire <specialist> for <gold>"); return true; }
            String role=rest.substring(0,at).trim();
            int price;
            try { price=Integer.parseInt(rest.substring(at+5).replaceAll("[^0-9]","")); }
            catch (NumberFormatException ex) { System.out.println("Specify an integer Gold price."); return true; }
            if(role.isBlank() || price<=0) { System.out.println("Invalid hiring order."); return true; }
            PlayerState player=state.player(playerId);
            Map<ResourceType,Integer> cost=Map.of(ResourceType.GOLD,price);
            if(!player.resources().canAfford(cost)) { System.out.println("Not enough Gold."); return true; }
            player.resources().spend(cost);
            hires.merge(role,1,Integer::sum);
            log("Hired "+role+" for "+price+" Gold.");
            return true;
        }
        if (low.startsWith("list ")) {
            String[] fields=line.substring(5).split(" for ");
            if(fields.length!=2) { System.out.println("Use: list <resource name> for <gold price>"); return true; }
            int price;
            try { price=Integer.parseInt(fields[1].trim().split(" ")[0]); }
            catch(NumberFormatException ex){ System.out.println("Gold price must be a number."); return true; }
            if(price<=0 || !stocks.containsKey(fields[0]) && stocks.keySet().stream().noneMatch(k->k.equalsIgnoreCase(fields[0].trim()))) {
                System.out.println("Unknown stored commodity or invalid price."); return true;
            }
            String resource=stocks.keySet().stream().filter(k->k.equalsIgnoreCase(fields[0].trim())).findFirst().orElse(fields[0].trim());
            marketPrices.put(resource,price);
            log("Listed "+resource+" at "+price+" Gold/unit. No sale has occurred.");
            return true;
        }
        if (low.startsWith("sell ")) {
            String[] words=line.substring(5).trim().split("\\s+",2);
            if(words.length<2) {System.out.println("Use: sell <quantity> <commodity>");return true;}
            int count;
            try {count=Integer.parseInt(words[0]);}catch(NumberFormatException ex){System.out.println("Quantity must be numeric.");return true;}
            String resource=stocks.keySet().stream().filter(k->k.equalsIgnoreCase(words[1].trim())).findFirst().orElse(null);
            if(resource==null || count<=0 || stocks.get(resource)<count || !marketPrices.containsKey(resource)){
                System.out.println("Insufficient listed stock. Set price with: list <commodity> for <gold price>");return true;
            }
            // This is an explicit local buyer transaction, not a speculative automatic sale.
            stocks.put(resource,stocks.get(resource)-count);
            int proceeds=count*marketPrices.get(resource);
            state.player(playerId).resources().add(ResourceType.GOLD,proceeds);
            log("Sold "+count+" "+resource+" for "+proceeds+" Gold to the local market.");
            return true;
        }
        if (low.equals("caravan trip")) {
            if(!caravanEnabled) {System.out.println("Establish trade caravan first.");return true;}
            caravanTrips++;
            state.player(playerId).resources().add(ResourceType.GOLD,1);
            log("Completed paid client transport trip #"+caravanTrips+" (+1 Gold).");
            return true;
        }
        if (low.equals("auto mine on") || low.equals("automatically develop discoveries")) {
            autoMining = true;
            log("Automatic development enabled: viable discoveries will be mined when Wealth permits.");
            developQueued(state.player(playerId),state.round());
            return true;
        }
        if (low.equals("auto mine off")) {
            autoMining = false; log("Automatic mine development disabled."); return true;
        }
        if (low.startsWith("assign ") || low.startsWith("order ")) {
            int to = low.indexOf(" to ");
            if (to < 0) { System.out.println("Use: assign <deployed unit> to <standing order>"); return true; }
            int first = low.indexOf(' ')+1;
            String cardName = line.substring(first,to).trim();
            String instruction = line.substring(to+4).trim();
            CardInstance unit = findDeployed(state.player(playerId),cardName);
            if (unit == null) { System.out.println("No deployed card named: "+cardName); return true; }
            assignments.put(unit.id(),instruction);
            log("Standing order: "+unit.definition().name()+" -> "+instruction);
            if (instruction.toLowerCase(Locale.ROOT).contains("explor")) exploring = true;
            return true;
        }
        if (low.equals("explore") || low.equals("start exploration")) {
            if (!hasExplorer(state.player(playerId))) {
                System.out.println("Deploy a Beastmaster, Treasure Hunter, Scout, or similar explorer first.");
                return true;
            }
            exploring = true;
            log("Exploration activated. One resource survey per production round.");
            return true;
        }
        if (low.startsWith("develop ")) {
            String wanted = line.substring(8).trim();
            String key = matchDeposit(wanted);
            if (key == null) { System.out.println("Unknown deposit. View 'discoveries'."); return true; }
            develop(key,state.player(playerId),state.round());
            return true;
        }
        return false;
    }

    public void onRound(GameState state,String playerId) {
        PlayerState human=state.player(playerId);
        if (exploring && hasExplorer(human) && lastSurveyRound != state.round()) {
            lastSurveyRound=state.round();
            if (exploredSites < DISCOVERIES.length) {
                String name=DISCOVERIES[exploredSites], key=name.toLowerCase(Locale.ROOT);
                deposits.put(key,new Deposit(name,COSTS[exploredSites],YIELDS[exploredSites],false,0));
                exploredSites++;
                log("DISCOVERY: "+name+" (develop with "+COSTS[exploredSites-1]+" Wealth).");
            } else {
                log("Exploration survey completed: no new deposit this round.");
            }
        }
        for (var entry:List.copyOf(deposits.entrySet())) {
            Deposit d=entry.getValue();
            if (d.developed && state.round() >= d.firstRound) {
                stocks.merge(d.name,d.yield,Integer::sum);
                log(d.name+" produced "+d.yield+" units.");
            }
        }
        if (autoMining) developQueued(human,state.round());
    }

    private boolean hasExplorer(PlayerState human) {
        return human.kingdom().stream().anyMatch(c ->
            c.definition().name().toLowerCase(Locale.ROOT).matches(".*(beastmaster|hunter|scout|ranger|cartographer).*"));
    }

    private CardInstance findDeployed(PlayerState player,String query) {
        return player.kingdom().stream().filter(c -> c.definition().name().equalsIgnoreCase(query))
            .findFirst().orElse(null);
    }

    private String matchDeposit(String query) {
        return deposits.keySet().stream().filter(k->k.equalsIgnoreCase(query)
                || k.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).findFirst().orElse(null);
    }

    private void developQueued(PlayerState player,int round) {
        for (String name:List.copyOf(deposits.keySet())) {
            Deposit d=deposits.get(name);
            if (!d.developed && player.resources().canAfford(Map.of(ResourceType.WEALTH,d.cost))) {
                develop(name,player,round);
            }
        }
    }

    private void develop(String key,PlayerState player,int round) {
        Deposit d=deposits.get(key);
        if (d.developed) { System.out.println(d.name+" already developed."); return; }
        Map<ResourceType,Integer> cost=Map.of(ResourceType.WEALTH,d.cost);
        if (!player.resources().canAfford(cost)) {
            System.out.println("Need "+d.cost+" Wealth to establish "+d.name+".");
            return;
        }
        player.resources().spend(cost);
        deposits.put(key,new Deposit(d.name,d.cost,d.yield,true,round+1));
        log("Established "+d.name+" mine for "+d.cost+" Wealth; production begins round "+(round+1)+".");
    }

    private void log(String event) {
        journal.add(event);
        System.out.println("[Kingdom] "+event);
    }

    public String dashboard(GameState state,String playerId) {
        StringBuilder out=new StringBuilder("\n=== YOUR KINGDOM | ROUND "+state.round()+" ===\n");
        out.append("Treasury: ").append(state.player(playerId).resources().snapshot()).append("\n");
        out.append("Automatic mining: ").append(autoMining ? "ON" : "OFF");
        out.append(" | Exploration: ").append(exploring ? "ACTIVE" : "INACTIVE").append("\n");
        out.append("Discoveries / resource mines:\n");
        if (deposits.isEmpty()) out.append("  None yet\n");
        for (Deposit d:deposits.values())
            out.append("  ").append(d.name).append(": ").append(d.developed ? "MINE (+"
                + d.yield+"/round starting R"+d.firstRound+")" : "UNDEVELOPED ("+d.cost+" Wealth)").append("\n");
        out.append("Stored goods: ").append(stocks).append("\n");
        out.append("Standing orders:\n");
        for (var entry:assignments.entrySet())
            out.append("  ").append(state.findCard(entry.getKey()).map(c->c.definition().name()).orElse(entry.getKey()))
               .append(" -> ").append(entry.getValue()).append("\n");
        out.append("Recent kingdom events:\n");
        for (int i=Math.max(0,journal.size()-5);i<journal.size();i++) out.append("  ").append(journal.get(i)).append("\n");
        return out.toString();
    }
}
