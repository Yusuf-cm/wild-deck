package com.wilddeck.app;

import java.util.*;
import java.util.regex.*;

/** Bounded, breakable promises for the live match, not canonical Official Contracts. */
final class RndDiplomacy {
 enum State { PENDING, ACCEPTED, REJECTED, CANCELLED, EXPIRED }
 record Offer(String id,String from,String to,String giveResource,int give,
              String wantResource,int want,int peaceRounds,int expiresRound,State state) {
  Offer withState(State next){return new Offer(id,from,to,giveResource,give,wantResource,want,peaceRounds,expiresRound,next);}
  boolean involves(String actor){return from.equals(actor)||to.equals(actor);}
 }
 record Pact(String id,String from,String to,int endsRound) {
  boolean binds(String a,String b){return from.equals(a)&&to.equals(b)||from.equals(b)&&to.equals(a);}
 }
 private static final String RESOURCE="(gold|wealth|mana)";
 private static final Pattern BARTER=Pattern.compile("give ([0-9]+) "+RESOURCE+" for ([0-9]+) "+RESOURCE+"(?: peace ([0-9]+))?",Pattern.CASE_INSENSITIVE);
 private static final Pattern PEACE=Pattern.compile("peace ([0-9]+)",Pattern.CASE_INSENSITIVE);
 private final RndMatchEngine game;
 private final Map<String,Offer> offers=new LinkedHashMap<>();
 private final Map<String,Pact> pacts=new LinkedHashMap<>();
 private final Map<String,Integer> breaches=new LinkedHashMap<>(),proposedThisTurn=new HashMap<>();
 private int nextId=1;
 RndDiplomacy(RndMatchEngine game){this.game=game;}
 record Snapshot(List<Offer> offers,List<Pact> pacts,Map<String,Integer> breaches,Map<String,Integer> proposedThisTurn,int nextId){}
 Snapshot snapshot(){return new Snapshot(List.copyOf(offers.values()),List.copyOf(pacts.values()),new LinkedHashMap<>(breaches),new LinkedHashMap<>(proposedThisTurn),nextId);}
 void restore(Snapshot s){
  offers.clear();s.offers().forEach(o->offers.put(o.id(),o));
  pacts.clear();s.pacts().forEach(p->pacts.put(p.id(),p));
  breaches.clear();breaches.putAll(s.breaches());proposedThisTurn.clear();proposedThisTurn.putAll(s.proposedThisTurn());nextId=s.nextId();
 }
 private RndMatchEngine.Result ok(String text){return new RndMatchEngine.Result(true,text);}
 private RndMatchEngine.Result fail(String text){return new RndMatchEngine.Result(false,text);}
 private void event(String text){game.diplomacyEvent(text);}
 boolean atPeace(String a,String b){return pacts.values().stream().anyMatch(p->p.endsRound()>game.round()&&p.binds(a,b));}
 int breaches(String actor){return breaches.getOrDefault(actor,0);}
 void turnEnded(String actor){proposedThisTurn.remove(actor);}
 void refresh(){
  for(Offer offer:List.copyOf(offers.values()))if(offer.state()==State.PENDING &&
      (offer.expiresRound()<=game.round()||game.eliminated(offer.from())||game.eliminated(offer.to()))){
   offers.put(offer.id(),offer.withState(State.EXPIRED));event(offer.id()+" expired; no resources moved");
  }
  for(Pact pact:List.copyOf(pacts.values()))if(pact.endsRound()<=game.round()||game.eliminated(pact.from())||game.eliminated(pact.to())){
   pacts.remove(pact.id());event(pact.id()+" ceasefire ended without a betrayal");
  }
 }
 RndMatchEngine.Result propose(String from,String raw){
  String[] parts=raw==null?new String[0]:raw.trim().split("\\s+",2);
  if(parts.length!=2)return fail(usage());
  String to;
  try{to=game.seat(parts[0]).id;}catch(IllegalArgumentException ex){return fail("choose another surviving kingdom");}
  if(to.equals(from)||game.eliminated(to))return fail("choose another surviving kingdom");
  String terms=parts[1].trim().replaceAll("\\s+"," ");
  Matcher barter=BARTER.matcher(terms),peace=PEACE.matcher(terms);
  String giveResource=null,wantResource=null;int give=0,want=0,peaceRounds=0;
  try{
   if(barter.matches()){
    give=Integer.parseInt(barter.group(1));want=Integer.parseInt(barter.group(3));
    giveResource=barter.group(2).toUpperCase(Locale.ROOT);wantResource=barter.group(4).toUpperCase(Locale.ROOT);
    if(give<1||want<1||give>1_000_000||want>1_000_000)return fail("trade amounts must be 1..1000000");
    if(giveResource.equals(wantResource))return fail("trade two different resource types");
    if(barter.group(5)!=null)peaceRounds=Integer.parseInt(barter.group(5));
    if(barter.group(5)!=null&&(peaceRounds<1||peaceRounds>10))return fail("peace duration must be 1..10 completed rounds");
   }else if(peace.matches()){
    peaceRounds=Integer.parseInt(peace.group(1));
    if(peaceRounds<1||peaceRounds>10)return fail("peace duration must be 1..10 completed rounds");
   }else return fail(usage());
  }catch(NumberFormatException ex){return fail("amount or duration is too large");}
  if(peaceRounds>0 && atPeace(from,to))return fail("a ceasefire with that kingdom is already active");
  if(peaceRounds>0 && game.diplomacyThreat(from,to))return fail("withdraw invading forces and resolve pending occupations before offering peace");
  if(offers.values().stream().anyMatch(o->o.state()==State.PENDING&&o.from().equals(from)&&o.to().equals(to)))return fail("cancel your pending offer to that kingdom first");
  if(proposedThisTurn.getOrDefault(from,0)>=3)return fail("only three offers per turn");
  if(balance(game.seat(from),giveResource)<give)return fail("you cannot fund the offered resources");
  String id="D"+nextId++;
  Offer offer=new Offer(id,from,to,giveResource,give,wantResource,want,peaceRounds,game.round()+2,State.PENDING);
  offers.put(id,offer);proposedThisTurn.merge(from,1,Integer::sum);
  event(from+" sent "+id+" to "+to+" (terms private until accepted)");
  return ok("Offer "+id+" sent to "+to+": "+terms(offer)+"; expires at round "+offer.expiresRound()+". Nothing transferred until acceptance; resources are not reserved.");
 }
 RndMatchEngine.Result respond(String actor,String action,String rawId){
  String id=rawId==null?"":rawId.trim().toUpperCase(Locale.ROOT);
  Offer offer=offers.get(id);
  if(offer==null||!offer.involves(actor))return fail("no such offer for your kingdom");
  if(offer.state()!=State.PENDING)return fail(id+" is already "+offer.state());
  boolean cancel=action.equals("CANCEL");
  if(!(cancel?offer.from():offer.to()).equals(actor))return fail(cancel?"only the proposer can cancel":"only the recipient can respond");
  if(cancel||action.equals("REJECT")){
   State next=cancel?State.CANCELLED:State.REJECTED;
   offers.put(id,offer.withState(next));event(actor+" "+next.name().toLowerCase(Locale.ROOT)+" "+id);
   return ok(id+" "+next+"; no resources moved");
  }
  if(offer.expiresRound()<=game.round()||game.eliminated(offer.from())||game.eliminated(offer.to()))return fail("offer expired");
  if(offer.peaceRounds()>0&&(atPeace(offer.from(),offer.to())||game.diplomacyThreat(offer.from(),offer.to())))return fail("peace terms are no longer valid; existing pact, invasion or occupation");
  var from=game.seat(offer.from());var to=game.seat(offer.to());
  if(balance(from,offer.giveResource())<offer.give()||balance(to,offer.wantResource())<offer.want())return fail("offer cannot settle: a kingdom lacks the promised resources; nothing changed");
  if((long)balance(to,offer.giveResource())+offer.give()>Integer.MAX_VALUE||
     (long)balance(from,offer.wantResource())+offer.want()>Integer.MAX_VALUE)return fail("resource balance overflow; nothing changed");
  // All checks precede both sides of the exchange and treaty registration.
  add(from,offer.giveResource(),-offer.give());add(to,offer.giveResource(),offer.give());
  add(to,offer.wantResource(),-offer.want());add(from,offer.wantResource(),offer.want());
  offers.put(id,offer.withState(State.ACCEPTED));
  if(offer.peaceRounds()>0)pacts.put(id,new Pact(id,offer.from(),offer.to(),game.round()+offer.peaceRounds()));
  event(id+" accepted: "+offer.from()+" -> "+offer.to()+": "+terms(offer));
  return ok(id+" ACCEPTED; exchange settled once"+(offer.peaceRounds()>0?"; ceasefire until round "+(game.round()+offer.peaceRounds())+" (exclusive). Betray explicitly to break it":""));
 }
 RndMatchEngine.Result betray(String actor,String rawTarget){
  String target;
  try{target=game.seat(rawTarget==null?"":rawTarget.trim()).id;}catch(IllegalArgumentException ex){return fail("choose a kingdom with an active ceasefire");}
  List<Pact> broken=pacts.values().stream().filter(p->p.endsRound()>game.round()&&p.binds(actor,target)).toList();
  if(broken.isEmpty())return fail("no active ceasefire with that kingdom");
  broken.forEach(p->pacts.remove(p.id()));breaches.merge(actor,1,Integer::sum);
  event(actor+" BETRAYED "+target+"; public broken promises: "+breaches(actor));
  return ok("Ceasefire broken with "+target+". Public broken promises: "+breaches(actor)+". No resource refund; hostile actions are now legal if normal combat rules allow.");
 }
 List<String> respondAsOpponent(String actor){
  List<String> responses=new ArrayList<>();
  for(Offer offer:List.copyOf(offers.values())){
   if(offer.state()!=State.PENDING||!offer.to().equals(actor))continue;
   boolean fair=value(offer.giveResource(),offer.give())>=value(offer.wantResource(),offer.want());
   boolean trusted=offer.peaceRounds()==0||breaches(offer.from())==0;
   var result=respond(actor,fair&&trusted?"ACCEPT":"REJECT",offer.id());
   if(!result.success())result=respond(actor,"REJECT",offer.id());
   responses.add(actor+": "+result.message());
  }
  return responses;
 }
 String view(String viewer){
  StringBuilder out=new StringBuilder("Diplomacy — breakable promises, not Official Contracts\nYour offers (only sender/recipient see pending terms):\n");
  for(Offer offer:offers.values())if(offer.involves(viewer))out.append(offer.id()).append(" ").append(offer.from()).append(" -> ").append(offer.to()).append(" | ").append(terms(offer)).append(" | ").append(offer.state()).append(" | offer expires R").append(offer.expiresRound()).append('\n');
  out.append("Public ceasefires:\n");
  for(Pact pact:pacts.values())out.append(pact.id()).append(" ").append(pact.from()).append(" <-> ").append(pact.to()).append(" until R").append(pact.endsRound()).append(" (exclusive)\n");
  out.append("Public broken promises: ").append(breaches).append("\n").append(usage()).append("\naccept D1 | reject D1 | cancel D1 | betray <kingdom>\nNPC values: Gold=1, Wealth=2, Mana=2; accepts affordable non-losing trades; refuses peace from promise-breakers. Peace forbids entering each other's capital or igniting the partner's capital or deployed cards' region.\n");
  return out.toString();
 }
 private static String usage(){return "offer <kingdom> give <amount> <gold|wealth|mana> for <amount> <resource> [peace <1..10>] | offer <kingdom> peace <1..10>";}
 private static String terms(Offer o){return (o.give()>0?o.from()+" gives "+o.give()+" "+o.giveResource()+", "+o.to()+" gives "+o.want()+" "+o.wantResource():"no resource exchange")+(o.peaceRounds()>0?"; peace "+o.peaceRounds()+" completed rounds":"");}
 private static long value(String resource,int amount){return (long)amount*("GOLD".equals(resource)?1:2);}
 private static int balance(RndMatchEngine.Seat seat,String resource){return resource==null?0:switch(resource){case "GOLD"->seat.gold;case "WEALTH"->seat.wealth;case "MANA"->seat.mana;default->throw new IllegalArgumentException("unknown resource");};}
 private static void add(RndMatchEngine.Seat seat,String resource,int amount){if(resource!=null)switch(resource){case "GOLD"->seat.gold+=amount;case "WEALTH"->seat.wealth+=amount;case "MANA"->seat.mana+=amount;default->throw new IllegalArgumentException("unknown resource");}}
}
