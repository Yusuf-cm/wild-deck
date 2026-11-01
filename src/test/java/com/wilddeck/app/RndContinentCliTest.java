package com.wilddeck.app;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class RndContinentCliTest {
 @Test void worldHasFourCapitalsAndSixConnectedRegions(){
  assertEquals(6,RndContinent.regions().size());
  assertEquals(4,RndContinent.regions().stream().filter(r->!r.owner().equals("neutral")).count());
  for(RndContinent.Region r:RndContinent.regions())
   for(String neighbor:r.borders())
    assertTrue(RndContinent.regions().stream().anyMatch(x->x.id().equals(neighbor)));
 }
 @Test void dashboardKeepsOtherPlayersPrivate(){
  RndContinentCli cli=new RndContinentCli(20261010L,new Scanner(""));
  String panel=cli.dashboard();
  assertTrue(panel.contains("FOUR KINGDOMS"));
  assertTrue(panel.contains("Runebank"));
  assertTrue(panel.contains("169")==false);
  assertTrue(panel.contains("Mind Veil"));
  assertTrue(panel.contains("ASHA"));
  String opponent=cli.submit("kingdom asha");
  assertTrue(opponent.contains("hidden unless yours"));
  String hidden=cli.engine().seat("asha").hand.values().iterator().next().name();
  assertFalse(opponent.contains(hidden));
  assertTrue(cli.submit("atlas").contains("Sunken Ruins"));
 }
 @Test void humanPassRunsThreeRivalsAndReturnsControl(){
  RndContinentCli cli=new RndContinentCli(20261010L,new Scanner(""));
  assertTrue(cli.submit("deploy Arcane Tower").contains("deployed"));
  assertTrue(cli.submit("pass").contains("New round: 1"));
  assertEquals("player",cli.engine().activeSeat());
  assertEquals(1,cli.engine().round());
  assertEquals(1,cli.engine().seat("player").mana);
 }
}
