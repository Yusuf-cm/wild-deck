package com.wilddeck.app;
import java.util.*;
/** Generic, deterministic rules for the historical three-kingdom replay. */
public final class KingdomRulesEngine {
 public record Result(boolean success,String message){}
 public record Card(String name,Set<String> abilities,int strength){}
 public record Mine(String resource,int yield,int firstRound){}
 public record Treaty(Set<String> signatories,int throughRound,boolean active){}
 public static final class Kingdom {
  public final String id; public double gold; public int wealth,mana;
  public final Map<String,Integer> goods=new LinkedHashMap<>();
  public final Map<String,Card> hand=new LinkedHashMap<>(),board=new LinkedHashMap<>();
  public final Map<String,Mine> mines=new LinkedHashMap<>();
  public final Map<String,String> orders=new LinkedHashMap<>();
  public final Set<String> creatures=new LinkedHashSet<>(), discoveries=new LinkedHashSet<>();
  Kingdom(String id){this.id=id;} public int stock(String name){return goods.getOrDefault(name,0);}
 }
 private final Map<String,Kingdom> kingdoms=new LinkedHashMap<>();
 private final Map<String,Card> cards=new LinkedHashMap<>();
 private final List<String> history=new ArrayList<>();
 private final Map<String,Integer> mineCosts=Map.of("Copper",2,"Silver",3,"Magic Crystal",3,"Gemstone",2,"Sapphire",3,"Mithril",4,"Iron",2);
 private final Map<String,Integer> yields=Map.of("Copper",2,"Silver",2,"Magic Crystal",1,"Gemstone",1,"Sapphire",1,"Mithril",1,"Iron",2);
 private Treaty treaty;private int cycle;
 public KingdomRulesEngine(){
  for(String id:List.of("player","asha","brian"))kingdoms.put(id,new Kingdom(id));
  define("Hydra",8,"ATTACK","REGENERATE");define("Militia",3,"ATTACK","DEFEND");
  define("Necromancer",0,"RAISE DEAD");define("Soul Lantern",0,"STORE SPIRIT");define("Cavalry",5,"ATTACK");define("Undead Cavalry",5,"ATTACK");
  define("Gold Mine",0,"PRODUCE GOLD");define("Mana Shrine",0,"PRODUCE MANA");
  define("Blacksmith",0,"FORGE");define("Beastmaster",0,"TAME");define("Treasure Hunter",0,"EXPLORE");
  define("Enchanter",0,"ENCHANT");define("Master Armorer",0,"ARMOR");define("Geologist",0,"SURVEY");
  define("Royal Trade Caravan",0,"TRANSPORT");define("Stormcaller",0,"LIGHTNING");
  define("Arcane Militia",5,"CAST","ATTACK");define("Dire Wolf",4,"TRACK");
  define("Stoneburrower",2,"DIG");define("Griffin",7,"FLY");define("River Serpent",9,"DIVE");
  define("Ancient Titan",12,"ATTACK");define("Black Plague",0,"INFECT");define("Mirror",0,"REFLECT");
  define("Mirror of Revelation",0,"REVEAL");define("Tunnel Map",0,"MAP");define("Assassin",3,"INFILTRATE");
  define("War Banner",0,"RALLY");
  for(String s:List.of("Merchant Fleet","Grand Alchemist","Royal Cartographer","Clockwork Golem","Trickster","Frost Giant","Dragon","Crystal Staff","Volcano"))define(s,0,"SPECIAL");
 }
 private void define(String n,int strength,String... caps){cards.put(n,new Card(n,Set.of(caps),strength));}
 public Kingdom kingdom(String id){Kingdom k=kingdoms.get(id);if(k==null)throw new IllegalArgumentException("unknown player: "+id);return k;}
 public List<String> history(){return List.copyOf(history);} public int cycle(){return cycle;}
 public void setCycle(int n){if(n<cycle)throw new IllegalArgumentException("cannot rewind");cycle=n;}
 public void checkpoint(String id,double gold,int wealth,int mana){if(gold<0||wealth<0||mana<0)throw new IllegalArgumentException();Kingdom k=kingdom(id);k.gold=gold;k.wealth=wealth;k.mana=mana;history.add("CHECKPOINT "+id+" R"+cycle+" (missing earlier accounting)");}
 public void checkpointStock(String id,String resource,int count){if(count<0)throw new IllegalArgumentException();kingdom(id).goods.put(resource,count);}
 public void giveCard(String id,String name){Card c=cards.get(name);if(c==null)throw new IllegalArgumentException("unknown card: "+name);kingdom(id).hand.put(name,c);}
 public Result deploy(String id,String name){Kingdom k=kingdom(id);Card c=k.hand.remove(name);if(c==null)return fail("card not in hand");k.board.put(name,c);history.add("R"+cycle+" "+id+" deployed "+name);return ok("deployed "+name);}
 public Result hire(String id,String name,int price){Kingdom k=kingdom(id);if(price<0||k.gold<price)return fail("insufficient Gold");k.gold-=price;k.board.put(name,cards.getOrDefault(name,new Card(name,Set.of("SPECIALIST"),0)));history.add("R"+cycle+" "+id+" hired "+name+" for "+price);return ok("hired "+name);}
 public Result order(String id,String card,String instruction){Kingdom k=kingdom(id);if(!k.board.containsKey(card))return fail("card not deployed");k.orders.put(card,instruction);return ok("order persisted");}
 public Result discover(String id,String name){kingdom(id).discoveries.add(name);history.add("R"+cycle+" "+id+" discovered "+name);return ok("discovered "+name);}
 public Result develop(String id,String resource){Kingdom k=kingdom(id);Integer cost=mineCosts.get(resource);if(cost==null)return fail("not a known deposit");if(!k.discoveries.contains(resource))return fail("not discovered");if(k.mines.containsKey(resource))return fail("mine exists");if(k.wealth<cost)return fail("insufficient Wealth");k.wealth-=cost;k.mines.put(resource,new Mine(resource,yields.get(resource),cycle+1));history.add("R"+cycle+" "+id+" developed "+resource);return ok("production begins next cycle");}
 public void advanceCycle(){cycle++;for(Kingdom k:kingdoms.values()){
   int count=k.mines.size()+(k.board.containsKey("Gold Mine")?1:0);
   k.wealth+=1+Math.max(0,(count-1)/2)+(k.board.containsKey("Royal Trade Caravan")?1:0);
   if(k.board.containsKey("Gold Mine"))k.gold++;
   if(k.board.containsKey("Mana Shrine"))k.mana++;
   for(Mine mine:k.mines.values())if(cycle>=mine.firstRound())k.goods.merge(mine.resource(),mine.yield(),Integer::sum);
  }history.add("R"+cycle+" production settled");}
 public Result trade(String buyer,String seller,String resource,int count,double price){
  if(count<=0||price<0)return fail("invalid transaction");Kingdom b=kingdom(buyer),s=kingdom(seller);
  if(s.stock(resource)<count)return fail("insufficient physical stock");
  if(b.gold+0.00001<price)return fail("buyer cannot afford transaction");
  s.goods.put(resource,s.stock(resource)-count);b.goods.merge(resource,count,Integer::sum);s.gold+=price;b.gold-=price;
  history.add("R"+cycle+" "+buyer+" bought "+count+" "+resource+" from "+seller+" for "+price+" Gold");return ok("trade settled");}
 public Result caravan(String customer,String carrier,int fee){Kingdom c=kingdom(customer),k=kingdom(carrier);
  if(!k.board.containsKey("Royal Trade Caravan"))return fail("no caravan");
  if(fee<0||c.gold<fee)return fail("unaffordable");c.gold-=fee;k.gold+=fee;history.add("R"+cycle+" paid caravan trip for "+customer);return ok("delivered and paid");}
 public Result craft(String id,String specialist,String item,Map<String,Integer> recipe){
  Kingdom k=kingdom(id);if(!k.board.containsKey(specialist))return fail("specialist absent");
  if(!k.board.get(specialist).abilities().contains("FORGE")&&!k.board.get(specialist).abilities().contains("ARMOR")&&!k.board.get(specialist).abilities().contains("ENCHANT"))return fail("card lacks crafting ability");
  for(var r:recipe.entrySet())if(r.getValue()<0||k.stock(r.getKey())<r.getValue())return fail("materials unavailable");
  for(var r:recipe.entrySet())k.goods.put(r.getKey(),k.stock(r.getKey())-r.getValue());
  k.goods.merge(item,1,Integer::sum);history.add("R"+cycle+" "+specialist+" crafted "+item);return ok("crafted "+item);}
 public Result tame(String id,String creature,int strength,boolean succeeded){
  Kingdom k=kingdom(id);if(!k.board.containsKey("Beastmaster"))return fail("requires Beastmaster");
  if(!k.discoveries.contains(creature))return fail("not discovered");
  if(!succeeded)return fail("taming attempt unsuccessful");
  Card c=cards.getOrDefault(creature,new Card(creature,Set.of("BIOLOGICAL","ATTACK"),strength));
  k.board.put(creature,c);k.creatures.add(creature);history.add("R"+cycle+" tamed "+creature);return ok("tamed "+creature);}
 public void treaty(Set<String> signatories,int end){for(String s:signatories)kingdom(s);treaty=new Treaty(Set.copyOf(signatories),end,true);history.add("R"+cycle+" signed treaty");}
 public Result withdraw(String id){if(treaty==null||!treaty.active()||!treaty.signatories().contains(id))return fail("no active agreement");treaty=new Treaty(treaty.signatories(),treaty.throughRound(),false);history.add("R"+cycle+" "+id+" withdrew from pact");return ok("withdrawal recorded");}
 public Result attack(String id,String other,String card){Kingdom a=kingdom(id);kingdom(other);
  if(treaty!=null&&treaty.active()&&cycle<=treaty.throughRound()&&treaty.signatories().containsAll(Set.of(id,other)))return fail("non-aggression pact blocks attack");
  if(!a.board.containsKey(card))return fail("attacker not deployed");
  return fail("defender reaction and target selection required; damage not invented");}
 private static Result ok(String m){return new Result(true,m);}private static Result fail(String m){return new Result(false,m);}
}
