package com.wilddeck.app;

import java.util.*;

/** Fresh Age of Chaos R&D match, entirely separate from the old 200-card seed. */
public final class AgeOfChaosEngine {
 public record Card(String name,String type,int wealthCost,int manaCost,int strength,String ability){}
 // Legacy wealthCost/manaCost fields are now intended activation-cost metadata, never deployment charges.
 public record Result(boolean success,String message){}
 public record Treaty(String first,String second,boolean trade,boolean nonAggression,boolean mutualDefense){}
 public static final List<String> TURN_ORDER=List.of("thornveil","emberfall","sunspire","dreadhaven");
 public static final Set<String> HUMAN_KINGDOMS=Set.of("thornveil","sunspire");
 public static final Set<String> AI_KINGDOMS=Set.of("emberfall","dreadhaven");
 private static final LinkedHashMap<String,List<Card>> OPENING=new LinkedHashMap<>();
 static{
  OPENING.put("thornveil",List.of(
   new Card("The Witch of Hollow Roots","Specialist",2,0,2,"Spend 1 Timber: summon defensive Briar Wall (Strength 3), once per round; parley with wild creatures"),
   new Card("King of the Wild Herd","Creature",2,0,5,"Carry one specialist across bordering regions; once per game retreat into wilderness instead of dying"),
   new Card("The Living Bridge","Structure",2,0,0,"Link bordering regions; once per round friendly travel along the link consumes no movement"),
   new Card("Lantern Thief","Specialist",1,0,1,"Attempt to steal a resource; may be intercepted by guards"),
   new Card("Orchard of Second Chances","Structure",2,0,0,"Produce 1 Timber each round; sacrifice once to recover one destroyed creature"),
   new Card("Whispermoth Swarm","Creature",1,0,1,"Spy on one neighboring Kingdom and reveal one deployed trap or standing military order"),
   new Card("Gravebloom Seed","Relic",1,1,0,"Plant in a region; after two rounds produces one rare Gravebloom; may interact with death magic")
  ));
  OPENING.put("sunspire",List.of(
   new Card("The Last Dragon Egg","Relic",2,0,0,"Hatches in two completed rounds into Strength 5 young dragon; risky magical acceleration possible"),
   new Card("The Seven Oathbreakers","Military",2,0,6,"Veterans can retreat from a suicidal command once per round; loyalty may be negotiated"),
   new Card("Crown of Borrowed Kings","Artifact",0,2,0,"Once per round copy one visible activated card ability and pay normal activation cost"),
   new Card("Heartforge","Structure",2,0,0,"Produces 1 Iron each completed round; 2 Iron can forge basic gear"),
   new Card("Crimson Hour","Spell",0,2,0,"One military unit gets +3 Strength this round, then loses its next attack"),
   new Card("Sun Eater's Lens","Artifact",0,1,0,"Once per round store 1 sunlight; spend 2 sunlight to reveal one hidden regional hazard"),
   new Card("Glass Dune Riders","Military",2,0,4,"May cross one desert border free each round; gain +1 Strength when defending deserts")
  ));
  OPENING.put("emberfall",List.of(
   new Card("Cinderbound Captain","Military",2,0,4,"Organize allied infantry; +1 Strength when defending with another unit"),
   new Card("Ashen Treasury","Structure",2,0,0,"Produces 1 Gold per completed round"),
   new Card("Coal Drake","Creature",2,0,3,"Flight over one adjacent region; can ignite unsecured timber"),
   new Card("Smoke Weaver","Specialist",1,1,1,"Conceal one deployed unit until scouted"),
   new Card("Lava Channel","Structure",1,1,0,"Alter one own region's defenses with flowing lava"),
   new Card("Ember Coin","Artifact",1,0,0,"Once per round offer a coin for a private favor; transfers require acceptance"),
   new Card("Black Banner","Military",2,0,3,"May challenge another deployed military unit")
  ));
  OPENING.put("dreadhaven",List.of(
   new Card("Saltbone Corsairs","Military",2,0,4,"Raid coasts and carry one stolen resource if victorious"),
   new Card("Tideglass Oracle","Specialist",1,1,1,"Look at one unrevealed coastal event"),
   new Card("Drowned Bell","Artifact",0,2,0,"Call a lost unit to return once, if a valid remains source exists"),
   new Card("Harbor of Knives","Structure",2,0,0,"Produces one trade token per round"),
   new Card("Reef Serpent","Creature",2,0,5,"Defends coastal regions; cannot travel over mountains unaided"),
   new Card("Smuggler's Ledger","Utility",1,0,0,"Record a secret bilateral deal; no effect until both sides consent"),
   new Card("Storm Lantern","Equipment",1,1,0,"Protect an equipped ship or specialist from one storm event")
  ));
 }
 public static final class Kingdom{
  public final String id;
  public int wealth=3,mana=2,gold=1;
  public final LinkedHashMap<String,Card> hand=new LinkedHashMap<>(),board=new LinkedHashMap<>();
  public final Map<String,Integer> inventory=new LinkedHashMap<>();
  public final Map<String,String> orders=new LinkedHashMap<>();
  private Kingdom(String id){this.id=id;}
 }
 private final LinkedHashMap<String,Kingdom> kingdoms=new LinkedHashMap<>();
 private final List<String> history=new ArrayList<>();
 private final List<Treaty> treaties=new ArrayList<>();
 public List<Treaty> treaties(){return List.copyOf(treaties);}
 public boolean allied(String a,String b){return treaties.stream().anyMatch(t->(t.first().equals(a)&&t.second().equals(b)||t.first().equals(b)&&t.second().equals(a))&&t.mutualDefense());}
 public Result attack(String actor,String target){
  if(!turn().equals(actor))return new Result(false,"Not your turn");
  if(kingdoms.get(target)==null||actor.equals(target))return new Result(false,"Invalid target");
  if(treaties.stream().anyMatch(t->t.nonAggression() && (t.first().equals(actor)&&t.second().equals(target)||t.first().equals(target)&&t.second().equals(actor))))return new Result(false,"Treaty prohibits conflict between "+actor+" and "+target);
  return new Result(false,"Combat resolution requires a defender reaction; no damage applied");
 }
 public Result signMutualTreaty(String first,String second){
  if(!HUMAN_KINGDOMS.contains(first)||!HUMAN_KINGDOMS.contains(second)||first.equals(second))return new Result(false,"Both rulers must authorize treaty");
  if(allied(first,second))return new Result(false,"Treaty already active");
  treaties.add(new Treaty(first,second,true,true,true));
  history.add("Round "+round+": "+first+" and "+second+" signed a trade, non-aggression and mutual defense treaty");
  return new Result(true,"Treaty signed: trade, non-aggression and mutual defense");
 }
 // Small extensible draw pile: new cards are appended between matches, not fabricated on demand.
 private final Deque<Card> drawPile=new ArrayDeque<>(List.of(
  new Card("The Mirror Fox","Creature",0,0,3,"Once each round, mimic the appearance of one visible creature; disguise does not copy Strength or abilities"),
  new Card("Stormglass Compass","Artifact",0,0,0,"Detect one hidden route or weather hazard in an adjacent region"),
  new Card("The Bone Collector","Specialist",0,0,2,"Gather one Bone from a battlefield containing actual remains"),
  new Card("Gilded Spider","Creature",0,0,2,"Weave one trap in a controlled region; trap becomes visible when triggered"),
  new Card("The Wandering Market","Event",0,0,0,"Offer a public trade fair; trades require bilateral consent"),
  new Card("Griffin Hatchling","Creature",0,0,3,"May mature after two completed rounds if properly fed")
 ));
 public int drawPileSize(){return drawPile.size();}
 public Result draw(String actor){
  if(!turn().equals(actor))return new Result(false,"It is "+turn()+"'s turn");
  if(spent.contains(actor))return new Result(false,"Main action already used");
  if(drawPile.isEmpty())return new Result(false,"Draw pile empty");
  Card c=drawPile.removeFirst();
  kingdom(actor).hand.put(c.name(),c);
  spent.add(actor);
  history.add("Round "+round+": "+actor+" drew one private card");
  return new Result(true,"Drew "+c.name()+" — "+c.ability());
 }

 private int round=1,turnIndex=0;
 private final Set<String> spent=new HashSet<>();
 private AgeOfChaosEngine(){
  for(String id:TURN_ORDER){
   Kingdom k=new Kingdom(id);
   for(Card c:OPENING.get(id))k.hand.put(c.name(),c);
   kingdoms.put(id,k);
  }
  history.add("Round 1: Four kingdoms formed; seven secret cards dealt each; no cards played.");
 }
 public static AgeOfChaosEngine newGame(){return new AgeOfChaosEngine();}
 public Kingdom kingdom(String name){Kingdom k=kingdoms.get(name.toLowerCase(Locale.ROOT));if(k==null)throw new IllegalArgumentException("Unknown kingdom");return k;}
 public int round(){return round;}
 public String turn(){return TURN_ORDER.get(turnIndex);}
 public List<String> history(){return List.copyOf(history);}
 public Result play(String actor,String card){
  if(!turn().equals(actor))return new Result(false,"It is "+turn()+"'s turn");
  if(spent.contains(actor))return new Result(false,"Main action already used");
  Kingdom k=kingdom(actor);
  Card c=k.hand.get(card);
  if(c==null)return new Result(false,"Card not in hand");
  // Deployment is always free. Resources pay for activated abilities, crafting and other effects.
  k.hand.remove(card);k.board.put(card,c);spent.add(actor);
  history.add("Round "+round+": "+actor+" deployed "+card);
  return new Result(true,actor+" deployed "+card+" for free (Wealth "+k.wealth+", Mana "+k.mana+")");
 }
 public Result orderWildScouts(String actor){
  if(!turn().equals(actor))return new Result(false,"It is "+turn()+"'s turn");
  Kingdom k=kingdom(actor);
  if(!k.board.containsKey("The Witch of Hollow Roots"))return new Result(false,"The Witch is not deployed");
  k.orders.put("The Witch of Hollow Roots","Ask wild animals to search controlled territory for natural resources");
  history.add("Round "+round+": "+actor+" commissioned wild animal resource scouts");
  return new Result(true,"Wild animals begin surveying Thornveil. Findings arrive at round settlement.");
 }
 public Result pass(String actor){
  if(!turn().equals(actor))return new Result(false,"It is "+turn()+"'s turn");
  spent.remove(actor);history.add("Round "+round+": "+actor+" passed");
  turnIndex=(turnIndex+1)%TURN_ORDER.size();
  if(turnIndex==0){
   for(Kingdom k:kingdoms.values()){
    k.wealth++;
    if(k.board.containsKey("The Witch of Hollow Roots") && k.orders.containsKey("The Witch of Hollow Roots")){
     // Seeded first discovery: woodland scouts report accessible fallen timber.
     k.inventory.merge("Timber",1,Integer::sum);
     history.add("Round "+(round+1)+": "+k.id+" wild scouts recovered 1 Timber");
    }
    if(k.board.containsKey("Heartforge"))k.inventory.merge("Iron",1,Integer::sum);
    if(k.board.containsKey("Orchard of Second Chances"))k.inventory.merge("Timber",1,Integer::sum);
    if(k.board.containsKey("Ashen Treasury"))k.gold++;
   }
   round++;history.add("Round "+round+": completed-round production settled");
  }
  return new Result(true,"Next: "+turn()+" | Round "+round);
 }
 public String dashboard(String viewer){
  Kingdom self=kingdom(viewer);
  StringBuilder sb=new StringBuilder("AGE OF CHAOS | Round "+round+" | Current: "+turn()+"\n");
  for(Kingdom k:kingdoms.values())
   sb.append(k.id).append(" | board ").append(k.board.keySet())
     .append(" | hand ").append(k.id.equals(viewer)?k.hand.keySet():k.hand.size()+" hidden cards").append("\n");
  sb.append("Active treaties: ").append(treaties).append("\\n");
  sb.append("YOUR "+viewer.toUpperCase(Locale.ROOT)).append(" | Wealth ").append(self.wealth)
    .append(" Mana ").append(self.mana).append(" Gold ").append(self.gold)
    .append(" | goods ").append(self.inventory).append(" | orders ").append(self.orders);
  return sb.toString();
 }
 public Map<String,Integer> openingHandSizes(){
  LinkedHashMap<String,Integer> sizes=new LinkedHashMap<>();
  kingdoms.forEach((id,k)->sizes.put(id,k.hand.size()));
  return Collections.unmodifiableMap(sizes);
 }
}
