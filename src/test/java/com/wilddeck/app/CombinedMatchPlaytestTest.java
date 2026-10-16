package com.wilddeck.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

/** Reproducible headless rehearsal. The recorded winner must still be browser-tested. */
class CombinedMatchPlaytestTest {
 private record Trace(long seed,String winner,int round,List<String> log,String finalView){}
 private boolean unit(RndMatchEngine.Card c){return c.strength()>0&&c.capabilities().contains("ATTACK");}
 private boolean act(RndMatchEngine g,List<String> log,String command){
  int round=g.round();var result=g.command("player",command);
  if(result.success())log.add("R"+round+" | "+command+" => "+result.message().replace('\n',' '));
  return result.success();
 }
 private void pass(RndMatchEngine g,List<String> log){act(g,log,"pass");log.add("NPC R"+g.round()+" => "+g.takeOpponentTurns());}
 private int strength(RndMatchEngine g,String owner,String region){return g.seat(owner).board.values().stream().filter(c->region.equals(g.cardLocation(c.id()).orElse(null))).mapToInt(c->g.effectiveStrength(c,region)).sum();}
 private String step(RndMatchEngine g,String from,String destination){
  if(from==null||from.equals(destination))return null;
  Map<String,String> first=new HashMap<>();Deque<String> queue=new ArrayDeque<>();first.put(from,null);queue.add(from);
  while(!queue.isEmpty()){
   String at=queue.removeFirst();
   for(String next:g.neighbors(at).stream().sorted().toList()){
    if(first.containsKey(next)||g.isBurning(next))continue;
    String result=at.equals(from)?next:first.get(at);
    if(next.equals(destination))return result;first.put(next,result);queue.addLast(next);
   }
  }
  return null;
 }
 private Trace attempt(long seed){
  var g=RndMatchEngine.startQuick(seed);var log=new ArrayList<String>();
  var support=g.seat("player").hand.values().stream().filter(c->unit(c)&&c.capabilities().contains("OCCUPY")).max(Comparator.comparingInt(RndMatchEngine.Card::strength)).orElseThrow();
  act(g,log,"offer mira peace 10");act(g,log,"deploy "+support.id());act(g,log,"move "+support.id()+" C1");pass(g,log);
  boolean prepared=false;
  for(int i=0;i<4&&!prepared&&!g.eliminated("player");i++){
   prepared=act(g,log,"combine WD-171 with "+support.id()+" to flush N1");
   if(!prepared){var extra=g.seat("player").hand.values().stream().filter(this::unit).max(Comparator.comparingInt(RndMatchEngine.Card::strength)).orElse(null);if(extra!=null)act(g,log,"deploy "+extra.id());}
   pass(g,log);
  }
  if(!prepared||g.events().stream().noneMatch(e->e.contains("RETREATED N1 ->")))return null;
  if(!act(g,log,"use WD-165 on N1"))return null;
  String target="asha";
  for(int turn=0;turn<80&&g.winner().isEmpty();turn++){
   if(!g.eliminated("player")){
    if(g.eliminated(target))target=List.of("asha","brian","mira").stream().filter(id->!g.eliminated(id)).findFirst().orElse(target);
    if(g.atPeace("player",target))act(g,log,"betray "+target);
    var selected=g.seat("player").hand.values().stream().filter(this::unit).max(Comparator.comparingInt(c->c.strength()+(c.capabilities().contains("OCCUPY")?20:0))).orElse(null);
    act(g,log,selected==null?"draw":"deploy "+selected.id());
    for(String rival:List.of("asha","brian","mira"))if(!g.eliminated(rival)&&strength(g,rival,"W1")>0&&strength(g,"player","W1")>strength(g,rival,"W1"))act(g,log,"repel "+rival);
    String capital=RndMatchEngine.home(target);
    for(var card:List.copyOf(g.seat("player").board.values()))if(unit(card)){
     String location=g.cardLocation(card.id()).orElse(null),next=step(g,location,capital);
     if(next!=null)act(g,log,"move "+card.id()+" "+next);
    }
    if(strength(g,"player",capital)>strength(g,target,capital))act(g,log,"attack "+target);
    act(g,log,"conquer "+target);
   }
   pass(g,log);
  }
  if(g.winner().isEmpty())return null;
  if(log.stream().noneMatch(line->line.contains("attack ")&&line.contains("=>")))return null;
  return new Trace(seed,g.winner().orElseThrow(),g.round(),log,g.view("player"));
 }
 @Test void rehearseACompleteNormalMatchUsingDiplomacyCombatAndCombinedTerrain(){
  // Selected only for the human's natural hand: Wildfire, Great Flood and a
  // capable occupation unit. No opponent private hand is consulted by the plan.
  long[] seeds={789,900,941,1046,1083,1151,1174,1195,1230,1269,1535,1560,1598,1716,2435,2456,2633,2645,2663,2833,2851,2916,2990,3012,3217,3279,3284,3359,3362,3473};
  Trace chosen=null;
  for(long seed:seeds){var trace=attempt(seed);if(trace==null)continue;if(chosen==null)chosen=trace;if(trace.winner().equals("player")){chosen=trace;break;}}
  assertNotNull(chosen,"No complete rehearsal with an actual combined retreat; inspect rules/plan before live promotion");
  System.out.println("COMBINED_PLAYTEST seed="+chosen.seed()+" winner="+chosen.winner()+" round="+chosen.round());
  chosen.log().forEach(line->System.out.println("COMBINED_TRACE "+line));
  System.out.println("COMBINED_FINAL "+chosen.finalView());
 }
}
