package com.wilddeck.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MatchSnapshotTest {
 private final ObjectMapper json=new ObjectMapper();
 private RndMatchEngine roundTrip(RndMatchEngine g)throws Exception{
  var saved=g.snapshot();
  var restored=RndMatchEngine.restore(json.readValue(json.writeValueAsBytes(saved),RndMatchEngine.Snapshot.class));
  assertEquals(saved,restored.snapshot());
  for(String id:List.of("player","asha","brian","mira"))assertEquals(g.view(id),restored.view(id));
  return restored;
 }
 private static void yes(RndMatchEngine g,String actor,String command){
  var result=g.command(actor,command);assertTrue(result.success(),command+": "+result.message());
 }
 @Test void orderedDeckPrivateHandsAndFutureTurnsRoundTripForBothPools()throws Exception{
  for(boolean quick:List.of(true,false)){
   var original=quick?RndMatchEngine.startQuick(42):RndMatchEngine.start(42);
   var restored=roundTrip(original);
   for(int turn=0;turn<16;turn++){
    String actor=original.activeSeat();
    for(String command:List.of("offer "+(actor.equals("asha")?"brian":"asha")+" peace 3","draw","pass")){
     assertEquals(original.command(actor,command),restored.command(actor,command));
     assertEquals(original.snapshot(),restored.snapshot());
     restored=roundTrip(restored);
    }
   }
  }
 }
 @Test void pendingCombinedOrderRetainsActualDefenderWindowAndDiplomacyIds()throws Exception{
  var g=RndMatchEngine.startQuick(42);
  var army=new RndMatchEngine.Card("army","Army","Military",Set.of("ATTACK","DEFEND","OCCUPY"),5);
  var guard=new RndMatchEngine.Card("guard","Guard","Military",Set.of("ATTACK","DEFEND","OCCUPY"),5);
  var fire=new RndMatchEngine.Card("ember","Ember","Event",Set.of("WORLD_EFFECT","IGNITE"),0,Set.of("FIRE"));
  g.seat("player").hand.put("army",army);g.seat("player").hand.put("ember",fire);
  yes(g,"player","deploy army");yes(g,"player","move army C1");yes(g,"player","pass");
  g.seat("asha").hand.put("guard",guard);yes(g,"asha","deploy guard");
  for(String actor:List.of("asha","brian","mira"))yes(g,actor,"pass");
  yes(g,"player","offer brian peace 3");yes(g,"player","combine ember with army to flush N1");
  var restored=roundTrip(g);
  assertFalse(restored.command("player","draw").success());
  for(String actor:List.of("player","asha","brian","mira")){
   assertEquals(g.command(actor,"pass"),restored.command(actor,"pass"));
   assertEquals(g.snapshot(),restored.snapshot());
   restored=roundTrip(restored);
  }
  assertEquals("N2",restored.cardLocation("guard").orElseThrow());
  assertTrue(restored.command("player","interactions").message().contains("RETREATED"));
 }
 @Test void everyMutableEngineFieldIsExplicitlyCoveredAndCollectionsAreDetached()throws Exception{
  Set<String> saved=new HashSet<>();
  for(var component:RndMatchEngine.Snapshot.class.getRecordComponents())saved.add(component.getName());
  Set<String> fixed=Set.of("world","turns","aliases");
  for(Field field:RndMatchEngine.class.getDeclaredFields()){
   if(Modifier.isStatic(field.getModifiers())||fixed.contains(field.getName()))continue;
   assertTrue(saved.contains(field.getName()),"Missing snapshot field: "+field.getName());
  }
  var g=RndMatchEngine.startQuick(42);var snapshot=g.snapshot();
  g.seat("player").hand.clear();g.seat("player").goods.put("WOOD",9);
  assertEquals(7,snapshot.seats().get("player").hand().size());
  assertFalse(snapshot.seats().get("player").goods().containsKey("WOOD"));
  var restored=RndMatchEngine.restore(snapshot);restored.seat("asha").hand.clear();
  assertEquals(7,snapshot.seats().get("asha").hand().size());
 }
 @Test void nonDefaultCombatEconomyOccupationAndVictoryStateRoundTrips()throws Exception{
  var g=RndMatchEngine.startQuick(1598);
  Map<String,Object> values=new LinkedHashMap<>();
  values.put("attachments",Map.of("gear","carrier"));values.put("treatedThisRound",Set.of("healer"));
  values.put("locations",Map.of("carrier","N1","gear","N1"));values.put("wounds",Map.of("carrier",2));
  values.put("completedTurns",Map.of("asha",3));values.put("eliminated",Set.of("mira"));
  values.put("occupations",Map.of("N1",new RndMatchEngine.Occupation("player","asha",2,3)));
  values.put("footholds",Set.of("N1"));values.put("burning",Set.of("N1"));
  values.put("movedThisRound",Set.of("carrier"));values.put("mainActionSpent",Set.of("player"));
  values.put("hostileActionSpent",Set.of("player"));
  for(var e:values.entrySet()){
   Field f=RndMatchEngine.class.getDeclaredField(e.getKey());f.setAccessible(true);Object target=f.get(g);
   if(target instanceof Map map){map.clear();map.putAll((Map)e.getValue());}
   else if(target instanceof Set set){set.clear();set.addAll((Set)e.getValue());}
  }
  g.seat("player").goods.put("WOOD",4);g.seat("player").orders.put("carrier","defend");
  g.seat("player").gold=81;g.seat("player").mana=3;
  roundTrip(g);
  Field winner=RndMatchEngine.class.getDeclaredField("winner");winner.setAccessible(true);winner.set(g,"player");
  var restored=roundTrip(g);
  assertFalse(restored.command("player","draw").success());
  assertFalse(restored.command("player","pass").success());
 }
 @Test void acceptedPactsBetrayalsAndOfferCountersContinueAfterRestoration()throws Exception{
  var g=RndMatchEngine.startQuick(42);
  yes(g,"player","offer asha peace 3");yes(g,"player","pass");
  yes(g,"asha","accept D1");yes(g,"asha","offer brian peace 2");
  for(String actor:List.of("asha","brian","mira"))yes(g,actor,"pass");
  var restored=roundTrip(g);assertTrue(restored.atPeace("player","asha"));
  for(String command:List.of("betray asha","offer mira peace 2")){
   assertEquals(g.command("player",command),restored.command("player",command));
   assertEquals(g.snapshot(),restored.snapshot());restored=roundTrip(restored);
  }
  assertFalse(restored.atPeace("player","asha"));
  assertTrue(restored.command("player","deals").message().contains("D3"));
 }
 @Test void invalidVersionsAreRejected()throws Exception{
  var node=json.valueToTree(RndMatchEngine.startQuick(42).snapshot());
  ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("version",99);
  var saved=json.treeToValue(node,RndMatchEngine.Snapshot.class);
  assertThrows(IllegalArgumentException.class,()->RndMatchEngine.restore(saved));
 }
}
