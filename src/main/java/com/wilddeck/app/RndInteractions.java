package com.wilddeck.app;

import java.util.*;

/** Executable, capability-based combined orders with real defender response windows. */
final class RndInteractions {
 private record Flush(String id,String actor,String support,String blockingRegion,String region,Map<String,Integer> defenderTurns){}
 private final RndMatchEngine game;
 private final Map<String,Flush> pending=new LinkedHashMap<>();
 private final List<String> history=new ArrayList<>();
 private int nextId=1;
 RndInteractions(RndMatchEngine game){this.game=game;}
 private RndMatchEngine.Result fail(String text){return new RndMatchEngine.Result(false,text);}
 private void record(String text){history.add(text);game.diplomacyEvent(text);}
 RndMatchEngine.Result flush(String actorId,String sourceId,String supportId,String rawRegion){
  var actor=game.seat(actorId);
  var source=RndMatchEngine.find(actor.hand,sourceId);
  if(source==null)source=RndMatchEngine.find(actor.board,sourceId);
  if(source==null||!source.properties().contains("FIRE")||!source.capabilities().contains("IGNITE"))return fail("combination needs your FIRE + IGNITE source, not a card-name recipe");
  var support=RndMatchEngine.find(actor.board,supportId);
  if(support==null||!support.capabilities().contains("ATTACK")||!game.mobile(support)||game.remainingStrength(support.id())<=0)return fail("support must be your living deployed mobile ATTACK unit");
  if(source.id().equals(support.id()))return fail("combine two distinct owned cards");
  String region=rawRegion==null?"":rawRegion.trim().toUpperCase(Locale.ROOT);
  if(!game.forest(region))return fail("flush needs a FLAMMABLE FOREST region; use world for IDs");
  String blockingRegion=game.cardLocation(support.id()).orElse("");
  if(!game.neighbors(region).contains(blockingRegion))return fail("support must occupy an adjacent region to block one escape route");
  if(game.effectiveStrength(support,blockingRegion)<=0)return fail("support has no effective strength at its position");
  if(pending.containsKey(region))return fail("a combined flush is already pending at that region");
  if(actor.wealth<1)return fail("combined order needs 1 Wealth for coordination supplies");
  Map<String,Integer> defenders=new LinkedHashMap<>();
  for(String other:game.kingdomIds()){
   if(other.equals(actorId)||game.eliminated(other))continue;
   if(game.seat(other).board.values().stream().anyMatch(c->vulnerable(c,region)))defenders.put(other,game.completedTurns(other));
  }
  if(defenders.isEmpty())return fail("no vulnerable enemy mobile defenders at that forest; fireproof units and fixed structures cannot be flushed");
  // Reuse the authoritative terrain resolver for ownership, range, diplomacy,
  // card-action, flammability, existing fire and Mana checks before any cost.
  var ignition=game.execute(new RndMatchEngine.Order(actorId,"TERRAIN",source.id(),region,null,Map.of()));
  if(!ignition.success())return ignition;
  actor.wealth--;
  String id="X"+nextId++;
  pending.put(region,new Flush(id,actorId,support.id(),blockingRegion,region,Map.copyOf(defenders)));
  String result=id+": combined flush prepared at "+region+"; FIRE + IGNITE from "+source.name()+", ATTACK pressure from "+support.name()+" at "+blockingRegion+". -1 Wealth; main and hostile actions spent. Awaiting actual defender turns: "+defenders.keySet()+". Keep support in place; water cancels; fireproof units hold. Fire still blocks your ordinary advance.";
  record(result);return new RndMatchEngine.Result(true,result);
 }
 private boolean vulnerable(RndMatchEngine.Card card,String region){
  return game.mobile(card)&&game.remainingStrength(card.id())>0&&!game.hasProperty(card,"FIREPROOF")&&region.equals(game.cardLocation(card.id()).orElse(null));
 }
 void extinguished(String region){
  Flush flush=pending.remove(region);
  if(flush!=null)record(flush.id()+": combined flush COUNTERED by extinguishing "+region+"; no forced retreat, costs not refunded");
 }
 void matchFinished(){
  for(Flush flush:pending.values())record(flush.id()+": combined flush CANCELLED — match finished");
  pending.clear();
 }
 void settle(){
  for(Flush flush:List.copyOf(pending.values())){
   var support=game.seat(flush.actor()).board.get(flush.support());
   String cancel=null;
   if(game.eliminated(flush.actor()))cancel="coordinating kingdom conquered";
   else if(!game.isBurning(flush.region()))cancel="fire extinguished";
   else if(support==null||!flush.blockingRegion().equals(game.cardLocation(flush.support()).orElse(null))||game.effectiveStrength(support,flush.blockingRegion())<=0)cancel="support lost, moved or ineffective";
   else if(game.protectedRegion(flush.actor(),flush.region()))cancel="a ceasefire now protects the target region";
   if(cancel!=null){pending.remove(flush.region());record(flush.id()+": combined flush CANCELLED — "+cancel+"; costs not refunded");continue;}
   if(flush.defenderTurns().entrySet().stream().anyMatch(e->!game.eliminated(e.getKey())&&game.completedTurns(e.getKey())<=e.getValue()))continue;
   pending.remove(flush.region());
   List<String> outcomes=new ArrayList<>();
   for(String owner:game.kingdomIds()){
    if(!flush.defenderTurns().containsKey(owner)||game.eliminated(owner))continue;
    for(var unit:List.copyOf(game.seat(owner).board.values())){
     if(!vulnerable(unit,flush.region()))continue;
     String escape=escape(owner,flush);
     if(escape==null)outcomes.add(owner+"'s "+unit.name()+" PINNED at "+flush.region()+" (no legal escape; no automatic damage or death)");
     else{game.forceRetreat(unit,escape);outcomes.add(owner+"'s "+unit.name()+" RETREATED "+flush.region()+" -> "+escape);}
    }
   }
   record(flush.id()+": combined flush RESOLVED after defender response; "+(outcomes.isEmpty()?"no vulnerable defenders remained":String.join("; ",outcomes))+". Fire persists; no raid, capture or conquest awarded.");
  }
 }
 private String escape(String owner,Flush flush){
  return game.neighbors(flush.region()).stream()
   .filter(region->!region.equals(flush.blockingRegion())&&!game.isBurning(region))
   .filter(region->game.kingdomIds().stream().noneMatch(other->!other.equals(owner)&&game.atPeace(owner,other)&&RndMatchEngine.home(other).equals(region)))
   .filter(region->game.kingdomIds().stream().filter(other->!other.equals(owner)&&!game.eliminated(other)).noneMatch(other->game.seat(other).board.values().stream().anyMatch(c->region.equals(game.cardLocation(c.id()).orElse(null))&&game.effectiveStrength(c,region)>0&&(c.capabilities().contains("ATTACK")||c.capabilities().contains("DEFEND")))))
   .sorted(Comparator.comparingInt((String region)->region.equals(RndMatchEngine.home(owner))?0:game.neutralRegion(region)?1:2).thenComparing(Comparator.naturalOrder()))
   .findFirst().orElse(null);
 }
 String view(){
  StringBuilder out=new StringBuilder("Combined orders — pending:\n");
  for(Flush flush:pending.values())out.append(flush.id()).append(" ").append(flush.actor()).append(" flushing ").append(flush.region()).append("; support ").append(flush.support()).append(" blocks ").append(flush.blockingRegion()).append("; awaiting actual response from ").append(flush.defenderTurns().keySet()).append('\n');
  out.append("Recent outcomes:\n");
  history.stream().skip(Math.max(0,history.size()-8)).forEach(line->out.append(line).append('\n'));
  return out.append("combine <fire-source> with <troop> to flush <forest-id> | interactions\nCosts: one main + one hostile action, 1 Wealth, normal source/card/Mana cost. Water cancels; FIREPROOF holds; support must stay adjacent. Forced retreat carries equipment and spends the unit's movement in the resolution round.\n").toString();
 }
 String summary(){return pending.values().stream().map(f->f.id()+" flush "+f.region()+"; "+f.support()+" blocks "+f.blockingRegion()+"; awaiting defender response").toList().toString();}
}
