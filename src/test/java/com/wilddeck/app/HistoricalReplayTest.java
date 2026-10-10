package com.wilddeck.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalReplayTest {
    @Test void replaysFromRoundZeroToEndOfCycleEighteen() {
        HistoricalReplay replay = new HistoricalReplay();
        assertEquals(18,replay.cycle());
        assertEquals(21.4,replay.gold(),0.001);
        assertEquals(2,replay.wealth());
        assertEquals(8,replay.mana());
        assertTrue(replay.hand().contains("Ancient Titan"));
        assertFalse(replay.deployed().contains("Ancient Titan"));
        assertTrue(replay.deployed().contains("River Serpent"));
        assertEquals(6,replay.inventory().get("Iron"));
        assertEquals(1,replay.inventory().get("Sapphire"));
        assertEquals(0,replay.inventory().get("Mithril"));
    }
    @Test void replayReportsChronologyGapsRatherThanFillingInEvents() {
        HistoricalReplay replay = new HistoricalReplay();
        assertTrue(replay.timeline().stream().anyMatch(e->e.cycle()==0 && !e.verified()));
        assertTrue(replay.timeline().stream().anyMatch(e->e.cycle()==11 && e.verified()));
        assertTrue(replay.timeline().stream().anyMatch(e->e.cycle()==17 && e.verified()));
        assertTrue(replay.gaps().stream().anyMatch(g->g.contains("Cycles 1-10")));
        assertTrue(replay.report().contains("CHRONOLOGY GAP"));
    }
    @Test void replayDoesNotProduceMithrilBeforeCycleNineteen(){
        HistoricalReplay replay=new HistoricalReplay();
        assertEquals(0,replay.inventory().get("Mithril"));
        assertEquals(4,replay.inventory().get("Copper"));
        assertEquals(5,replay.inventory().get("Silver"));
        assertEquals(1,replay.inventory().get("Magic Crystal"));
    }
}
