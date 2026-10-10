package com.wilddeck.app;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThreeKingdomScenarioTest {
    @Test void cycle18RestoresKingdomAndMaintainsInventories() {
        ThreeKingdomScenario s=new ThreeKingdomScenario();
        assertEquals(18,s.cycle());
        assertTrue(s.summary().contains("21.4"));
        assertTrue(s.summary().contains("Ancient Titan"));
        assertTrue(s.summary().contains("River Serpent"));
        assertTrue(s.summary().contains("Mithril=0"));
        assertTrue(s.command("next round").contains("Cycle 19"));
        assertTrue(s.summary().contains("Mithril=1"));
    }
    @Test void deployFreeAndTreatyMustBeBrokenBeforeAttack(){
        ThreeKingdomScenario s=new ThreeKingdomScenario();
        assertEquals("Deployed Ancient Titan for free.",s.command("deploy Ancient Titan"));
        assertTrue(s.command("attack Brian").contains("peace treaty"));
        assertTrue(s.command("break treaty").contains("terminated"));
        assertTrue(s.command("attack Brian").contains("cannot yet resolve combat"));
    }
    @Test void provisionalDrawIsExplicitlyNotRandom(){
        ThreeKingdomScenario s=new ThreeKingdomScenario();
        assertTrue(s.command("draw").contains("deterministic"));
        assertTrue(s.summary().contains("Frost Giant"));
    }
}
