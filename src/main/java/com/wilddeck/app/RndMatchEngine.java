package com.wilddeck.app;

import com.fasterxml.jackson.databind.*;
import com.wilddeck.world.*;
import java.io.*;
import java.util.*;

/** Four-seat R&D match: deterministic cards and a strict translator/engine boundary. */
public final class RndMatchEngine {
 public record Card(String id,String name,String category,Set<String> capabilities,int strength,Set<String> properties){
  public Card(String id,String name,String category,Set<String> capabilities,int strength){this(id,name,category,capabilities,strength,Set.of());}
 }
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
 private final WorldMap world = continentWorld();
 private final Map<String,String> locations = new LinkedHashMap<>();
 public record Occupation(String attacker,String defender,int startedRound,int defenderTurnsCompleted){}
 private final Map<String,String> capitalControllers=new LinkedHashMap<>();
 private final Map<String,Integer> completedTurns=new HashMap<>();
 private final Map<String,Integer> wounds=new HashMap<>();
 private final Set<String> eliminated=new LinkedHashSet<>();
 private final Map<String,Occupation> occupations=new LinkedHashMap<>();
 private final Set<String> footholds=new HashSet<>();
 private String winner;
 public Optional<String> winner(){return Optional.ofNullable(winner);}
 public boolean eliminated(String id){return eliminated.contains(id);}
 public int remainingStrength(String cardId){
  for(Seat seat:seats.values()){Card c=seat.board.get(cardId);if(c!=null)return Math.max(0,c.strength()-wounds.getOrDefault(cardId,0));}
  return 0;
 }
 private final Set<String> burning = new HashSet<>();
 private final List<Card> discarded = new ArrayList<>();
 private final Set<String> movedThisRound = new HashSet<>();
 private static final Set<String> MOBILE = Set.of("MOVE","ROAM","ATTACK","OCCUPY","FLY","BURROW","INFILTRATE");
 private static WorldMap continentWorld(){
  List<WorldLocation> regions = new ArrayList<>();
  Map<String,Set<String>> links = new LinkedHashMap<>();
  for(var r:RndContinent.regions()){
   regions.add(new WorldLocation(r.id(),r.name(),r.terrain().equals("Forest") ? Set.of("FOREST","FLAMMABLE") : Set.of(r.terrain().toUpperCase(Locale.ROOT))));
   links.put(r.id(),new LinkedHashSet<>(r.borders()));
  }
  return new WorldMap(regions,links);
 }
 private static String home(String actor){
  return RndContinent.regions().stream().filter(r->r.owner().equals(actor)).findFirst().orElseThrow().id();
 }
 public Optional<String> cardLocation(String cardId){return Optional.ofNullable(locations.get(cardId));}
 public String worldView(String viewer){
  Seat owner=seat(viewer);
  StringBuilder out=new StringBuilder("World — round "+round+" | Current: "+activeSeat()+"\n");
  for(var r:RndContinent.regions())out.append(r.id()).append(" ").append(r.name()).append(" [").append(r.terrain()).append("] owner=").append(capitalControllers.getOrDefault(r.id(),r.owner())).append(" borders=").append(r.borders()).append(burning.contains(r.id()) ? " BURNING" : "").append('\n');
  out.append("Your deployed cards (use IDs or full names):\n");
  for(Card c:owner.board.values())out.append(c.id()).append(" ").append(c.name()).append(" @ ").append(locations.getOrDefault(c.id(),"unplaced")).append('\n');
  return out.toString();
 }
 public static final int FOUNDING_TRUCE_ROUNDS=0;
 private static final Set<String> HOSTILE_ACTIONS=Set.of("ATTACK","RAID","SIEGE","STEAL","SABOTAGE","DESTROY","INVADE","CAPTURE","PLUNDER");
 public boolean foundingTruceActive(){return round<FOUNDING_TRUCE_ROUNDS;}
 private static boolean offensiveInstruction(String text){return text!=null && text.toLowerCase(Locale.ROOT).matches("(?s).*\\b(?:attack|raid|siege|steal|sabotage|destroy|invade|plunder)\\b.*");}
 private final Set<String> mainActionSpent=new HashSet<>();
 private final Set<String> hostileActionSpent=new HashSet<>();
 // R&D card-effect registry: additive yields applied only at completed round boundaries.
 private static final Map<String,Map<String,Integer>> PRODUCTION_EFFECTS = Map.of(
   "Arcane Tower", Map.of("MANA",1));
 private final List<String> turns=List.of("player","asha","brian","mira");
 private final Map<String,String> aliases=Map.of("you","player","player1","player","asha","asha","brian","brian","mira","mira");
 private RndMatchEngine(){for(String id:turns){seats.put(id,new Seat(id));capitalControllers.put(home(id),id);}}
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
     n.path("strength").isNumber()?n.get("strength").asInt():0,
     propertiesOf(n)));
   }
   if(bag.size()!=200)throw new IllegalStateException("expected 200 cards");
   Collections.shuffle(bag,new Random(seed));g.deck.addAll(bag);
   for(int i=0;i<7;i++)for(String id:g.turns)g.drawSetup(id);
   g.events.add("R0: four kingdoms established, seven private cards each; seed "+seed);
   return g;
  }catch(IOException e){throw new IllegalStateException(e);}
 }
 private static Set<String> propertiesOf(JsonNode node){
  Set<String> properties=new LinkedHashSet<>();
  for(JsonNode p:node.path("properties"))properties.add(p.asText());
  return Set.copyOf(properties);
 }
 public boolean isBurning(String region){return burning.contains(region);}
 public int discardedCount(){return discarded.size();}
 private boolean mobile(Card c){return Set.of("Military","Creature","Specialist").contains(c.category()) && c.capabilities().stream().anyMatch(MOBILE::contains);}
 private int effectiveStrength(Card c,String region){return Math.max(0,c.strength()-wounds.getOrDefault(c.id(),0)-(burning.contains(region)&&!c.properties().contains("FIREPROOF")?1:0));}
 public Seat seat(String id){Seat s=seats.get(aliases.getOrDefault(id.toLowerCase(Locale.ROOT),id));if(s==null)throw new IllegalArgumentException("unknown player");return s;}
 public String activeSeat(){return turns.get(active);}
 public int round(){return round;}
 public int deckSize(){return deck.size();}
 public List<String> events(){return List.copyOf(events);}
 private void drawSetup(String id){Card card=deck.removeFirst();seat(id).hand.put(card.id,card);}
 public Result execute(Order order){
  if(order==null||order.actor()==null||order.type()==null)return fail("missing order fields");
  String id=aliases.getOrDefault(order.actor().toLowerCase(Locale.ROOT),order.actor());
  String type=order.type().toUpperCase(Locale.ROOT);
  if(Set.of("LOOK","WORLD","HELP","EVENTS").contains(type)){
   if(!seats.containsKey(id))return fail("unknown player");
   if(type.equals("LOOK"))return ok(view(id));
   if(type.equals("WORLD"))return ok(worldView(id));
   if(type.equals("EVENTS"))return ok(String.join("\n",events));
   return ok("world | status | events | draw | deploy <card> | move <card> to <region> | use <card> on <region> | attack <kingdom> | repel <kingdom> | conquer <kingdom> | pass. Capital occupation must survive the defender's next turn. Last independent kingdom wins. Damage persists; defeated units are discarded. Fire blocks entry and reduces local strength.");
  }
  if(winner!=null)return fail("Match finished. Winner: "+winner+". Start a new four player game to play again.");
  if(eliminated.contains(id))return type.equals("PASS")?ok("Spectating; advancing opponents."):fail("your kingdom is conquered; pass to spectate");
  if(!activeSeat().equals(id))return fail("not "+id+"'s turn (current: "+activeSeat()+")");
  Seat actor=seat(id);
  if(foundingTruceActive() && (HOSTILE_ACTIONS.contains(type) || (type.equals("ABILITY") && offensiveInstruction(order.intent()) && order.target()!=null && !order.target().equalsIgnoreCase(id))))
   return fail("Founding Truce: hostile actions against other kingdoms are prohibited until Round 6");
  switch(type){
   case "ATTACK", "REPEL" -> {
    boolean repel=type.equals("REPEL");
    if(hostileActionSpent.contains(id))return fail("only one raid or attack per turn");
    String target=order.target()==null?"":order.target().toLowerCase(Locale.ROOT);
    if(!seats.containsKey(target)||target.equals(id)||eliminated.contains(target))return fail("choose a different surviving kingdom");
    Seat enemy=seat(target);
    String battlefield=home(repel?id:target);
    List<Card> soldiers=actor.board.values().stream().filter(c->remainingStrength(c.id())>0 && (c.capabilities().contains("ATTACK") || (repel && c.capabilities().contains("DEFEND"))) && battlefield.equals(locations.get(c.id()))).toList();
    if(soldiers.isEmpty())return fail("no attacking forces at "+battlefield+"; move units to the target capital first");
    int attack=soldiers.stream().mapToInt(c->effectiveStrength(c,battlefield)).sum();
    int defense=enemy.board.values().stream().filter(c->battlefield.equals(locations.get(c.id()))).mapToInt(c->effectiveStrength(c,battlefield)).sum();
    if(attack==0)return fail("local forces have no effective strength");
    List<Card> defenders=enemy.board.values().stream().filter(c->remainingStrength(c.id())>0 && battlefield.equals(locations.get(c.id()))).toList();
    if(repel && defenders.isEmpty())return fail("no enemy forces at your capital");
    hostileActionSpent.add(id);
    List<String> casualties=new ArrayList<>();
    applyDamage(actor,soldiers,defense,casualties);
    applyDamage(enemy,defenders,attack,casualties);
    String exchange=" (strength "+attack+" vs "+defense+"; casualties: "+casualties+")";
    if(repel){
     events.add("R"+round+": "+id+" counterattacked "+target+" at "+battlefield+exchange);
     return ok("Counterattack resolved at "+battlefield+exchange);
    }
    if(attack>defense){
     footholds.add(id+":"+target);
     // A raid occupies a single target asset; destroying a kingdom is a separate victory mechanic.
     Card prize=enemy.board.values().stream().filter(c->c.strength()==0 && battlefield.equals(locations.get(c.id()))).findFirst().orElse(null);
     if(prize!=null){enemy.board.remove(prize.id());actor.board.put(prize.id(),prize);
      events.add("R"+round+": "+id+" captured "+prize.name()+" from "+target+" (attack "+attack+" vs "+defense+")");
      return ok("Raid succeeded: captured "+prize.name()+"; "+target+" remains in the game"+exchange);
     }
     events.add("R"+round+": "+id+" won a raid against "+target+"; no unsecured asset to capture");
     return ok("Won the raid; opponent survives, no asset available"+exchange);
    }
    events.add("R"+round+": "+target+" repelled "+id+" (attack "+attack+" vs "+defense+")");
    return ok("Attack repelled"+exchange);
   }
   case "CONQUER" -> {
    String target=order.target()==null?"":order.target().toLowerCase(Locale.ROOT);
    if(!seats.containsKey(target)||target.equals(id)||eliminated.contains(target))return fail("choose another surviving kingdom");
    if(occupations.containsKey(target))return fail("capital already has a pending occupation");
    if(!footholds.contains(id+":"+target))return fail("win a local raid against that kingdom first");
    if(!canHoldCapital(id,target))return fail("conquest needs an OCCUPY unit at the capital and no effective local defenders");
    occupations.put(target,new Occupation(id,target,round,completedTurns.getOrDefault(target,0)));
    events.add("R"+round+": "+id+" began occupying "+target+"; defender response window opened");
    return ok("Occupation started at "+home(target)+"; "+target+" has its next turn to defend or repel. Conquest settles at the next completed round.");
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
   case "TERRAIN" -> {
    Card c=find(actor.hand,order.cardId());boolean fromHand=c!=null;
    if(c==null)c=find(actor.board,order.cardId());
    if(c==null)return fail("source must be your card in hand or deployed");
    String target=order.target()==null?"":order.target().trim().toUpperCase(Locale.ROOT);
    var region=world.location(target);
    if(region.isEmpty())return fail("unknown region; use world for region IDs");
    boolean ignite=c.properties().contains("FIRE")&&c.capabilities().contains("IGNITE");
    boolean extinguish=c.properties().contains("WATER")&&c.capabilities().contains("EXTINGUISH");
    if(!ignite&&!extinguish)return fail("card supplies neither FIRE/IGNITE nor WATER/EXTINGUISH");
    if(fromHand && mainActionSpent.contains(id))return fail("main action already spent this turn");
    if(fromHand && !Set.of("Spell","Event").contains(c.category()))return fail("deploy this source before using it");
    if(!c.capabilities().contains("WORLD_EFFECT") && !target.equals(fromHand?home(id):locations.get(c.id())))return fail("source cannot reach that region; local effects require the same location");
    if(ignite && !region.get().hasProperty("FLAMMABLE"))return fail("terrain is not flammable");
    if(ignite && burning.contains(target))return fail("region is already burning");
    if(extinguish && !burning.contains(target))return fail("region is not burning");
    int mana=c.capabilities().contains("MAGIC")?2:0;
    if(actor.mana<mana)return fail("terrain magic requires 2 Mana");
    actor.mana-=mana;
    if(fromHand){actor.hand.remove(c.id());discarded.add(c);mainActionSpent.add(id);}
    if(ignite)burning.add(target);else burning.remove(target);
    String message=c.name()+ (ignite?" ignited ":" extinguished ")+target+"; "+(ignite?"non-fireproof entry blocked; local strength -1":"entry restored; strength penalty removed");
    events.add("R"+round+": "+id+" "+message);
    return ok(message);
   }
   case "WORLD" -> {return ok(worldView(id));}
   case "HELP" -> {return ok("world | status | events | draw | deploy <card-name-or-id> | move <card-name-or-id> to <region-id> | assign <card> to <task> | pass. Movement: adjacent regions, one move per unit per round; structures/equipment/resources stay fixed. attack <kingdom> requires ATTACK units at its capital; use <card> on <region-id> resolves IGNITE or EXTINGUISH. Fire blocks non-fireproof entry and reduces local strength by 1 per non-fireproof card.");}
   case "MOVE" -> {
    Card c=find(actor.board,order.cardId());
    if(c==null)return fail("source must be your deployed card");
    if(!mobile(c))return fail("card has no movement capability or is a fixed asset");
    String to=order.target()==null?"":order.target().trim().toUpperCase(Locale.ROOT);
    if(world.location(to).isEmpty())return fail("unknown region; use world for region IDs");
    String from=locations.get(c.id());
    if(from==null)return fail("source has no world location");
    if(!world.connected(from,to))return fail("destination is not adjacent; borders: "+world.neighbors(from));
    if(burning.contains(to) && !c.properties().contains("FIREPROOF"))return fail("destination is burning; extinguish it first or use a FIREPROOF unit");
    if(movedThisRound.contains(c.id()))return fail("card already moved this round");
    locations.put(c.id(),to);movedThisRound.add(c.id());
    events.add("R"+round+": "+id+" moved "+c.name()+" from "+from+" to "+to);
    return ok(c.name()+" moved from "+from+" to "+to);
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
    actor.hand.remove(c.id());actor.board.put(c.id(),c);locations.put(c.id(),home(id));mainActionSpent.add(id);
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
    completedTurns.merge(id,1,Integer::sum);
    events.add("R"+round+": "+id+" passed");mainActionSpent.remove(id);hostileActionSpent.remove(id);advanceTurn();
    return ok("Turn passed. Active: "+activeSeat()+"; round "+round);
   }
   default -> {return fail("unsupported action: "+type);}
  }
 }
 private void applyDamage(Seat owner,List<Card> forces,int amount,List<String> casualties){
  // DEFEND-capable troops absorb the first damage; totals are snapshotted before either side changes.
  List<Card> ordered=forces.stream().sorted(Comparator.comparing((Card c)->!c.capabilities().contains("DEFEND"))).toList();
  for(Card c:ordered){
   if(amount<=0)break;
   int hit=Math.min(amount,remainingStrength(c.id()));
   wounds.merge(c.id(),hit,Integer::sum);amount-=hit;
   if(remainingStrength(c.id())==0){owner.board.remove(c.id());owner.orders.remove(c.id());locations.remove(c.id());movedThisRound.remove(c.id());discarded.add(c);casualties.add(owner.id+"'s "+c.name());}
  }
 }
 private boolean canHoldCapital(String attacker,String defender){
  String capital=home(defender);
  return seat(attacker).board.values().stream().anyMatch(c->c.capabilities().contains("OCCUPY")&&capital.equals(locations.get(c.id()))&&effectiveStrength(c,capital)>0)
   && seat(defender).board.values().stream().noneMatch(c->capital.equals(locations.get(c.id()))&&effectiveStrength(c,capital)>0);
 }
 private void advanceTurn(){
  do{
   active=(active+1)%turns.size();
   if(active==0){round++;movedThisRound.clear();settleOccupations();settleRoundProduction();events.add("R"+round+": production phase settled");}
  }while(winner==null&&eliminated.contains(activeSeat()));
  if(winner!=null)active=turns.indexOf(winner);
 }
 private void settleOccupations(){
  for(Occupation claim:List.copyOf(occupations.values())){
   if(claim.startedRound()>=round||completedTurns.getOrDefault(claim.defender(),0)<=claim.defenderTurnsCompleted())continue;
   occupations.remove(claim.defender());
   if(eliminated.contains(claim.attacker())||eliminated.contains(claim.defender())||!canHoldCapital(claim.attacker(),claim.defender())){
    events.add("R"+round+": occupation of "+claim.defender()+" broken");continue;
   }
   Seat ruler=seat(claim.attacker()),fallen=seat(claim.defender());
   ruler.board.putAll(fallen.board);fallen.board.clear();
   fallen.goods.forEach((good,amount)->ruler.goods.merge(good,amount,Integer::sum));fallen.goods.clear();
   capitalControllers.replaceAll((region,controller)->controller.equals(fallen.id)?ruler.id:controller);discarded.addAll(fallen.hand.values());fallen.hand.clear();fallen.orders.clear();
   ruler.gold+=fallen.gold;ruler.wealth+=fallen.wealth;ruler.mana+=fallen.mana;fallen.gold=fallen.wealth=fallen.mana=0;
   eliminated.add(fallen.id);events.add("R"+round+": "+ruler.id+" conquered "+fallen.id);
   List<String> independent=turns.stream().filter(id->!eliminated.contains(id)).toList();
   if(independent.size()==1){winner=independent.get(0);occupations.clear();events.add("R"+round+": MATCH WON by "+winner);break;}
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
  int spectatorTurns=0;
  while(winner==null && !activeSeat().equals("player") && (!eliminated.contains("player")||spectatorTurns++<turns.size())){
   String id=activeSeat();
   Seat npc=seat(id);
   // React using only owned cards and public terrain/positions, before scripted development.
   for(Card source:java.util.stream.Stream.concat(npc.board.values().stream(),npc.hand.values().stream()).toList()){
    if(!burning.contains(home(id)))break;
    if(source.properties().contains("WATER")&&source.capabilities().contains("EXTINGUISH")){
     Result counter=execute(new Order(id,"TERRAIN",source.id(),home(id),null,Map.of()));
     if(counter.success()){reports.add(id+": "+counter.message());break;}
    }
   }
   List<String> invaders=turns.stream().filter(other->!other.equals(id)&&!eliminated.contains(other)&&seat(other).board.values().stream().anyMatch(c->mobile(c)&&home(id).equals(locations.get(c.id())))).toList();
   if(!invaders.isEmpty()){
    Card reinforcement=npc.hand.values().stream().filter(c->c.strength()>0&&(c.capabilities().contains("DEFEND")||c.capabilities().contains("ATTACK"))).max(Comparator.comparingInt(Card::strength)).orElse(null);
    if(reinforcement!=null){Result deployed=execute(new Order(id,"PLAY",reinforcement.id(),null,null,Map.of()));if(deployed.success())reports.add(id+": defensive reinforcement — "+deployed.message());}
    Result counterattack=execute(new Order(id,"REPEL",null,invaders.get(0),null,Map.of()));if(counterattack.success())reports.add(id+": "+counterattack.message());
   }
   for(String rival:turns){
    if(rival.equals(id)||eliminated.contains(rival))continue;
    Result claim=execute(new Order(id,"CONQUER",null,rival,null,Map.of()));if(claim.success())reports.add(id+": "+claim.message());
   }
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
   // R&D referee: variable choices, no permanent NPC character class or objective.
   // Seeded only to make bug reports reproducible. Chat referee may choose freely.
   Random choice=new Random(20261010L + 7919L*round + 101L*turns.indexOf(id));
   List<String> rivals=new ArrayList<>(turns.stream().filter(other->!eliminated.contains(other)).toList());
   rivals.remove(id);
   String rival=rivals.get(choice.nextInt(rivals.size()));
   int mood=choice.nextInt(5);
   if(mood==0 || mood==3){
    String offer=mood==0?"Shall we exchange information?":"I offer a temporary ceasefire; name your price.";
    Result proposal=execute(new Order(id,"NEGOTIATE",null,rival,offer,Map.of()));
    if(proposal.success())reports.add(id+": "+proposal.message());
   }
   if(mood==1 || mood==4){
    for(Card force:List.copyOf(npc.board.values())){
     if(!mobile(force)||!force.capabilities().contains("ATTACK"))continue;
     String step=nextStep(locations.get(force.id()),home(rival));
     if(step!=null){Result moved=execute(new Order(id,"MOVE",force.id(),step,null,Map.of()));if(moved.success())reports.add(id+": "+moved.message());}
    }
    Result raid=execute(new Order(id,"ATTACK",null,rival,null,Map.of()));
    if(raid.success()){reports.add(id+": "+raid.message());Result claim=execute(new Order(id,"CONQUER",null,rival,null,Map.of()));if(claim.success())reports.add(id+": "+claim.message());}
   }
   List<Card> operators=new ArrayList<>(npc.board.values());
   if(!operators.isEmpty() && choice.nextBoolean()){
    Card unit=operators.get(choice.nextInt(operators.size()));
    String[] jobs={"guard our stores","scout neighboring territory","prepare a surprise","investigate ruins","gather intelligence"};
    String job=jobs[choice.nextInt(jobs.length)];
    Result ordered=execute(new Order(id,"ORDER",unit.id(),null,job,Map.of()));
    if(ordered.success())reports.add(id+" ordered "+unit.name()+": "+job);
   }
   Card selected=null;
   if(!npc.hand.isEmpty() && choice.nextInt(4)!=0){
    List<Card> choices=new ArrayList<>(npc.hand.values());
    selected=choices.get(choice.nextInt(choices.size()));
   }
   if(selected!=null){
    Result played=execute(new Order(id,"PLAY",selected.id(),null,null,Map.of()));
    if(played.success())reports.add(played.message());
   }else{
    Result drawn=execute(new Order(id,"DRAW",null,null,null,Map.of()));
    if(drawn.success())reports.add(id+" drew one private card");
   }
   Result passed=execute(new Order(id,"PASS",null,null,null,Map.of()));
   if(!passed.success())throw new IllegalStateException(passed.message());
  }
  return reports;
 }
 private String nextStep(String from,String destination){
  if(from==null||from.equals(destination))return null;
  Map<String,String> first=new HashMap<>();Deque<String> queue=new ArrayDeque<>();
  first.put(from,null);queue.add(from);
  while(!queue.isEmpty()){
   String region=queue.removeFirst();
   for(String neighbor:world.neighbors(region).stream().sorted().toList()){
    if(first.containsKey(neighbor)||burning.contains(neighbor))continue;
    String step=region.equals(from)?neighbor:first.get(region);
    if(neighbor.equals(destination))return step;
    first.put(neighbor,step);queue.addLast(neighbor);
   }
  }
  return null;
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
   b.append(p.board.values().stream().map(c->c.name()+" @ "+locations.getOrDefault(c.id(),"unplaced")+(c.strength()>0?" [strength "+remainingStrength(c.id())+"/"+c.strength()+"]":"")).toList());
   if(eliminated.contains(id))b.append(" CONQUERED");
   b.append(" | hand ").append(id.equals(s.id)?p.hand.values().stream().map(c->c.name()+" ["+c.id()+"]").toList():"["+p.hand.size()+" hidden cards]");
   b.append("\n");
  }
  b.append("Your resources: GOLD ").append(s.gold).append(", WEALTH ").append(s.wealth).append(", MANA ").append(s.mana).append("\n");
  b.append("Your locations: ").append(s.board.values().stream().map(c->c.id()+" "+c.name()+" @ "+locations.getOrDefault(c.id(),"unplaced")).toList()).append("\n");
  b.append("Pending occupations: ").append(occupations.values()).append("\n");
  b.append("Match: ").append(winner==null?"ongoing":"WINNER "+winner).append("\n");
  b.append("Your orders: ").append(s.orders).append("\n");
  return b.toString();
 }
 /** Explicit parser boundary: a future language model outputs this schema, not mutations. */
 public Order translateSimple(String actor,String text){
  String cmd=text.trim(),low=cmd.toLowerCase(Locale.ROOT);
  if(low.equals("events"))return new Order(actor,"EVENTS",null,null,null,Map.of());
  if(low.equals("world")||low.equals("map"))return new Order(actor,"WORLD",null,null,null,Map.of());
  if(low.equals("help"))return new Order(actor,"HELP",null,null,null,Map.of());
  if(low.startsWith("conquer ")||low.startsWith("repel "))return new Order(actor,low.startsWith("conquer ")?"CONQUER":"REPEL",null,cmd.substring(cmd.indexOf(' ')+1).trim(),null,Map.of());
  if(low.startsWith("use scrying on "))return new Order(actor,"SCRY","WD-088",cmd.substring(15).trim(),null,Map.of("MANA",2));
  if(low.startsWith("use ")){
   int separator=low.lastIndexOf(" on ");
   if(separator>4)return new Order(actor,"TERRAIN",cmd.substring(4,separator).trim(),cmd.substring(separator+4).trim(),null,Map.of());
  }
  if(low.startsWith("move ")){
   int separator=low.lastIndexOf(" to ");
   if(separator>5)return new Order(actor,"MOVE",cmd.substring(5,separator).trim(),cmd.substring(separator+4).trim(),null,Map.of());
   String[] parts=cmd.split("\\s+");
   if(parts.length==3)return new Order(actor,"MOVE",parts[1],parts[2],null,Map.of());
   return new Order(actor,"MOVE",null,null,null,Map.of());
  }
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