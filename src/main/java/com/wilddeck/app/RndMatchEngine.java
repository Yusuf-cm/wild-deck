package com.wilddeck.app;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;

/** Four-seat R&D match: deterministic cards and a strict translator/engine boundary. */
public final class RndMatchEngine {
 public record Card(String id,String name,String category,Set<String> capabilities,int strength){}
 public record Order(String actor,String type,String cardId,String target,String intent,Map<String,Integer> costs){}
 public record Result(boolean success,String message){}
 public static final class Seat {
  public final String id;
  public int gold,wealth,mana;
  public final Map<String,Card> hand=new LinkedHashMap<>(),board=new LinkedHashMap<>();
  public final Map<String,Integer> goods=new LinkedHashMap<>();
  public final Map<String,String> orders=new LinkedHashMap<>();
  private Seat(String id){this.id=id;}
 }
 private final LinkedHashMap<String,Seat> seats=new LinkedHashMap<>();
 private final Deque<Card> deck=new ArrayDeque<>();
 private final List<String> events=new ArrayList<>();
 private int round=0,active=0;
 public static final int FOUNDING_TRUCE_ROUNDS=0;
 private static final Set<String> HOSTILE_ACTIONS=Set.of("ATTACK","RAID","SIEGE","STEAL","SABOTAGE","DESTROY","INVADE","CAPTURE","PLUNDER");
 public boolean foundingTruceActive(){return round<FOUNDING_TRUCE_ROUNDS;}
 private static boolean offensiveInstruction(String text){return text!=null && text.toLowerCase(Locale.ROOT).matches("(?s).*\\b(?:attack|raid|siege|steal|sabotage|destroy|invade|plunder)\\b.*");}
 private final Set<String> mainActionSpent=new HashSet<>();
 // R&D card-effect registry: additive yields applied only at completed round boundaries.
 private static final Map<String,Map<String,Integer>> PRODUCTION_EFFECTS = Map.of(
   "Arcane Tower", Map.of("MANA",1));
 private final List<String> turns=List.of("player","asha","brian","mira");
 private final Map<String,String> aliases=Map.of("you","player","player1","player","asha","asha","brian","brian","mira","mira");
 private RndMatchEngine(){for(String id:turns)seats.put(id,new Seat(id));}
 public static RndMatchEngine start(long seed){
  RndMatchEngine g=new RndMatchEngine();
  try(InputStream stream=RndMatchEngine.class.getResourceAsStream("/data/rnd-200-card-pool.json")){
   if(stream==null)throw new IllegalStateException("200 card pool not found");
   JsonNode root=new ObjectMapper().readTree(stream);
   List<Card> bag=new ArrayList<>();
   for(JsonNode n:root.get("cards")){
    Set<String> caps=new LinkedHashSet<>();
    for(JsonNode c:n.get("capabilities"))caps.add(c.asText());
    bag.add(new Card(n.get("id").asText(),n.get("name").asText(),
     n.get("category").asText(),Set.copyOf(caps),
     n.path("strength").isNumber()?n.get("strength").asInt():0));
   }
   if(bag.size()!=200)throw new IllegalStateException("expected 200 cards");
   Collections.shuffle(bag,new Random(seed));g.deck.addAll(bag);
   for(int i=0;i<7;i++)for(String id:g.turns)g.drawSetup(id);
   g.events.add("R0: four kingdoms established, seven private cards each; seed "+seed);
   return g;
  }catch(IOException e){throw new IllegalStateException(e);}
 }
 public Seat seat(String id){Seat s=seats.get(aliases.getOrDefault(id.toLowerCase(Locale.ROOT),id));if(s==null)throw new IllegalArgumentException("unknown player");return s;}
 public String activeSeat(){return turns.get(active);}
 public int round(){return round;}
 public int deckSize(){return deck.size();}
 public List<String> events(){return List.copyOf(events);}
 private void drawSetup(String id){Card card=deck.removeFirst();seat(id).hand.put(card.id,card);}
 public Result execute(Order order){
  if(order==null||order.actor()==null||order.type()==null)return fail("missing order fields");
  String id=aliases.getOrDefault(order.actor().toLowerCase(Locale.ROOT),order.actor());
  if(!activeSeat().equals(id))return fail("not "+id+"'s turn (current: "+activeSeat()+")");
  Seat actor=seat(id);String type=order.type().toUpperCase(Locale.ROOT);
  if(foundingTruceActive() && (HOSTILE_ACTIONS.contains(type) || (type.equals("ABILITY") && offensiveInstruction(order.intent()) && order.target()!=null && !order.target().equalsIgnoreCase(id))))
   return fail("Founding Truce: hostile actions against other kingdoms are prohibited until Round 6");
  switch(type){
   case "ATTACK" -> {
    String target=order.target()==null?"":order.target().toLowerCase(Locale.ROOT);
    if(!seats.containsKey(target)||target.equals(id))return fail("choose a different kingdom");
    Seat enemy=seat(target);
    List<Card> soldiers=actor.board.values().stream().filter(c->c.strength()>0).toList();
    if(soldiers.isEmpty())return fail("no deployed attacking forces");
    int attack=soldiers.stream().mapToInt(Card::strength).sum();
    int defense=enemy.board.values().stream().mapToInt(Card::strength).sum();
    Card attacker=soldiers.get(0);
    if(attack>defense){
     // A raid occupies a single target asset; destroying a kingdom is a separate victory mechanic.
     Card prize=enemy.board.values().stream().filter(c->c.strength()==0).findFirst().orElse(null);
     if(prize!=null){enemy.board.remove(prize.id());actor.board.put(prize.id(),prize);
      events.add("R"+round+": "+id+" captured "+prize.name()+" from "+target+" (attack "+attack+" vs "+defense+")");
      return ok("Victory: captured "+prize.name()+"; "+target+" remains in the game");
     }
     events.add("R"+round+": "+id+" won a raid against "+target+"; no unsecured asset to capture");
     return ok("Won the raid; opponent survives, no asset available");
    }
    events.add("R"+round+": "+target+" repelled "+id+" (attack "+attack+" vs "+defense+")");
    return ok("Attack repelled; no cards destroyed");
   }
   case "NEGOTIATE" -> {
    String target=order.target()==null?"":order.target().toLowerCase(Locale.ROOT);
    if(!seats.containsKey(target)||target.equals(id))return fail("choose another kingdom");
    if(order.intent()==null||order.intent().isBlank())return fail("missing terms");
    actor.orders.put("proposal:"+target,order.intent());
    events.add("R"+round+": "+id+" proposed terms to "+target);
    return ok("Proposal delivered to "+target+": "+order.intent()+" (not accepted yet)");
   }
   case "TRADE" -> {
    String target=order.target()==null?"":order.target().toLowerCase(Locale.ROOT);
    if(!seats.containsKey(target)||target.equals(id))return fail("choose another kingdom");
    if(order.intent()==null||order.intent().isBlank())return fail("missing offer");
    actor.orders.put("trade:"+target,order.intent());
    events.add("R"+round+": "+id+" offered a trade to "+target);
    return ok("Trade proposed, pending other ruler's acceptance");
   }
   case "LOOK" -> {return ok(view(id));}
   case "DRAW" -> {
    if(mainActionSpent.contains(id))return fail("main action already spent this turn; kingdom management only");
    if(deck.isEmpty())return fail("deck empty");
    // No more than one draw per visit to the current seat.
    if(events.stream().anyMatch(x->x.equals("DRAW_TURN:"+round+":"+id)))return fail("already drew this turn");
    Card c=deck.removeFirst();actor.hand.put(c.id,c);mainActionSpent.add(id);events.add("DRAW_TURN:"+round+":"+id);
    return ok("Drew "+c.name()+" ["+c.id()+"]");
   }
   case "PLAY" -> {
    if(mainActionSpent.contains(id))return fail("main action already spent this turn; kingdom management only");
    Card c=find(actor.hand,order.cardId());if(c==null)return fail("card not in hand");
    actor.hand.remove(c.id());actor.board.put(c.id(),c);mainActionSpent.add(id);
    events.add("R"+round+": "+id+" deployed "+c.name());
    String effect=PRODUCTION_EFFECTS.containsKey(c.name()) ? " (passive: +1 Mana at each completed round)" : "";
    return ok(id+" deployed "+c.name()+" for free"+effect);
   }
   case "SCRY" -> {
    if(mainActionSpent.contains(id))return fail("main action already spent this turn");
    Card spell=find(actor.hand,"Scrying");
    if(spell==null)return fail("Scrying must be in hand");
    if(actor.mana<2)return fail("Scrying requires 2 Mana");
    String target=order.target()==null?"":order.target().toLowerCase(Locale.ROOT);
    if(!seats.containsKey(target)||target.equals(id))return fail("specify another valid kingdom");
    actor.mana-=2;
    actor.hand.remove(spell.id());
    mainActionSpent.add(id);
    Seat other=seat(target);
    // Reveal only to caster; do not place private identities in the public log.
    events.add("R"+round+": "+id+" cast Scrying on "+target+" (-2 Mana)");
    return ok("Scrying reveals "+target+"'s "+other.hand.size()+" cards: "+
      other.hand.values().stream().map(c->c.name()+" ["+c.id()+"]").toList()+
      ". Mana remaining: "+actor.mana);
   }
   case "ORDER" -> {
    Card c=find(actor.board,order.cardId());if(c==null)return fail("source not deployed");
    if(order.intent()==null||order.intent().isBlank())return fail("empty instruction");
    if(foundingTruceActive() && offensiveInstruction(order.intent()))return fail("Founding Truce: hostile standing orders against other kingdoms cannot be issued before Round 6");
    actor.orders.put(c.id(),order.intent());events.add("R"+round+": "+id+" ordered "+c.name()+" to "+order.intent());
    return ok("Standing order saved for "+c.name());
   }
   case "ABILITY" -> {
    Card c=find(actor.board,order.cardId());if(c==null)return fail("source not deployed");
    String verb=order.intent()==null?"":order.intent().trim().toUpperCase(Locale.ROOT);
    if(!c.capabilities().contains(verb))return fail("card does not supply ability: "+verb);
    int mana=order.costs()==null?0:order.costs().getOrDefault("MANA",0);
    if(mana<0||actor.mana<mana)return fail("insufficient mana");
    // Card has the trait, but generic application may require target-specific resolver.
    return fail("ability validated; no target-specific outcome resolver for "+verb+" yet; state unchanged");
   }
   case "PASS" -> {
    events.add("R"+round+": "+id+" passed");mainActionSpent.remove(id);active=(active+1)%turns.size();
    if(active==0){round++; settleRoundProduction();events.add("R"+round+": production phase settled");}
    return ok("Turn passed. Active: "+activeSeat()+"; round "+round);
   }
   default -> {return fail("unsupported action: "+type);}
  }
 }
 private void settleRoundProduction(){
  for(Seat seat:seats.values())for(Card card:seat.board.values()){
   Map<String,Integer> effect=PRODUCTION_EFFECTS.get(card.name());
   if(effect==null)continue;
   seat.gold+=effect.getOrDefault("GOLD",0);
   seat.wealth+=effect.getOrDefault("WEALTH",0);
   seat.mana+=effect.getOrDefault("MANA",0);
   events.add("R"+round+": "+seat.id+" "+card.name()+" produced "+effect);
  }
 }
 /** Offline simulation for the three non-human seats; no AI key or hidden-hand leakage. */
 public List<String> takeOpponentTurns(){
  List<String> reports=new ArrayList<>();
  while(!activeSeat().equals("player")){
   String id=activeSeat();
   Seat npc=seat(id);
   // Preserve recorded R0-R4 opponent actions for the active match;
   // new adversarial strategy is enabled beginning with the current Round 5.
   if(round<5){
    Card c=npc.hand.values().stream().findFirst().orElse(null);
    if(c!=null){Result play=execute(new Order(id,"PLAY",c.id(),null,null,Map.of()));
      if(play.success())reports.add(play.message());
    }else{Result draw=execute(new Order(id,"DRAW",null,null,null,Map.of()));
      if(draw.success())reports.add(id+" drew a private card");
    }
    execute(new Order(id,"PASS",null,null,null,Map.of()));
    continue;
   }
   // Personality and board-driven strategic management, independent of draw RNG.
   String rival=id.equals("asha")?"brian":id.equals("brian")?"asha":"player";
   String objective=id.equals("asha")?"secure trade routes and defensive alliances":id.equals("brian")?"take control of strategic assets":"seek relics and exploit weak opponents";
   Result diplomacy=execute(new Order(id,"NEGOTIATE",null,rival,objective,Map.of()));
   if(diplomacy.success())reports.add(id+": "+diplomacy.message());
   int force=npc.board.values().stream().mapToInt(Card::strength).sum();
   int enemyForce=seat(rival).board.values().stream().mapToInt(Card::strength).sum();
   if(force>enemyForce && !npc.board.isEmpty()){
    Result raid=execute(new Order(id,"ATTACK",null,rival,null,Map.of()));
    if(raid.success())reports.add(id+": "+raid.message());
   }
   // Main-action policy chooses military/production when possible, otherwise draws.
   Card selected=npc.hand.values().stream()
      .sorted(Comparator.comparingInt((Card c)->c.strength()>0?0:
       c.category().equals("Structure")?1:c.category().equals("Specialist")?2:3))
      .findFirst().orElse(null);
   if(selected!=null){
    Result deployed=execute(new Order(id,"PLAY",selected.id(),null,null,Map.of()));
    if(deployed.success())reports.add(deployed.message());
   }else{
    Result drew=execute(new Order(id,"DRAW",null,null,null,Map.of()));
    if(drew.success())reports.add(id+" drew one private card");
   }
   Result passed=execute(new Order(id,"PASS",null,null,null,Map.of()));
   if(!passed.success())throw new IllegalStateException(passed.message());
  }
  return reports;
 }
 private static Card find(Map<String,Card> map,String cardId){
  if(cardId==null)return null;
  Card exact=map.get(cardId);if(exact!=null)return exact;
  List<Card> matches=map.values().stream().filter(c->c.name().equalsIgnoreCase(cardId.trim())).toList();
  return matches.size()==1?matches.get(0):null;
 }
 private static Result ok(String s){return new Result(true,s);}
 private static Result fail(String s){return new Result(false,s);}
 public String view(String viewer){
  Seat s=seat(viewer);
  StringBuilder b=new StringBuilder("Round "+round+" | Current: "+activeSeat()+" | Deck: "+deck.size()+"\n");
  for(String id:turns){Seat p=seat(id);b.append(id).append(" | board ");
   b.append(p.board.values().stream().map(Card::name).toList());
   b.append(" | hand ").append(id.equals(s.id)?p.hand.values().stream().map(c->c.name()+" ["+c.id()+"]").toList():"["+p.hand.size()+" hidden cards]");
   b.append("\n");
  }
  b.append("Your resources: GOLD ").append(s.gold).append(", WEALTH ").append(s.wealth).append(", MANA ").append(s.mana).append("\n");
  b.append("Your orders: ").append(s.orders).append("\n");
  return b.toString();
 }
 /** Explicit parser boundary: a future language model outputs this schema, not mutations. */
 public Order translateSimple(String actor,String text){
  String cmd=text.trim(),low=cmd.toLowerCase(Locale.ROOT);
  if(low.equals("look")||low.equals("table")||low.equals("status"))return new Order(actor,"LOOK",null,null,null,Map.of());
  if(low.equals("draw")||low.equals("draw a card"))return new Order(actor,"DRAW",null,null,null,Map.of());
  if(low.equals("pass")||low.equals("end turn"))return new Order(actor,"PASS",null,null,null,Map.of());
  if(low.startsWith("deploy ")||low.startsWith("play "))return new Order(actor,"PLAY",null,null,null,Map.of());
  if(low.startsWith("negotiate ")||low.startsWith("trade ")){
   String[] parts=cmd.split(" ",3);
   if(parts.length==3)return new Order(actor,parts[0].equalsIgnoreCase("trade")?"TRADE":"NEGOTIATE",null,parts[1],parts[2],Map.of());
  }
  if(low.startsWith("attack ")||low.startsWith("raid ")||low.startsWith("siege ")||low.startsWith("steal ")||low.startsWith("sabotage ")||low.startsWith("invade "))return new Order(actor,low.split(" ")[0].toUpperCase(Locale.ROOT),null,cmd.substring(cmd.indexOf(" ")+1).trim(),cmd,Map.of());
  if(low.startsWith("scry ")||low.startsWith("use scrying on "))return new Order(actor,"SCRY","WD-088",low.startsWith("scry ")?cmd.substring(5).trim():cmd.substring(15).trim(),null,Map.of("MANA",2));
  if(low.startsWith("assign ")){
   int x=low.indexOf(" to ");if(x>7)return new Order(actor,"ORDER",cmd.substring(7,x),null,cmd.substring(x+4),Map.of());
  }
  return new Order(actor,"UNSUPPORTED",null,null,cmd,Map.of());
 }
 public Result command(String actor,String text){
  Order o=translateSimple(actor,text);
  if(o.type().equals("PLAY")){
   int i=text.indexOf(' ');o=new Order(actor,"PLAY",text.substring(i+1).trim(),null,null,Map.of());
  }
  return execute(o);
 }
}