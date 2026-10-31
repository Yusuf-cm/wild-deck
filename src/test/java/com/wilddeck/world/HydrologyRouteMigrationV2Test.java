package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologyRouteMigrationV2Test {
    @Test void reroutesDueWaterAtTheSameLandingWithoutInventingCatchmentIdentity() {
        int[] previous = {1,3,3,-1,-1};
        int[] next = {2,3,4,-1,-1};
        var first = HydrologyRouteMigrationV2.verify(previous,next,Set.of(3,4),List.of(0));
        var second = HydrologyRouteMigrationV2.verify(previous,next,Set.of(4,3),List.of(0));
        assertEquals(first,second);
        assertEquals(Map.of(0,3),first.previousOutlets());
        assertEquals(Map.of(0,4),first.nextOutlets());
        assertEquals(Set.of(0,2),first.changedVertices());
        assertArrayEquals(new int[]{1,3,3,-1,-1},previous);
        assertArrayEquals(new int[]{2,3,4,-1,-1},next);
    }

    @Test void confluencesAndMultipleLandingsRetainUniqueOwnerAddresses() {
        int[] previous = {2,2,3,-1};
        int[] next = {2,2,3,-1};
        var audit = HydrologyRouteMigrationV2.verify(previous,next,Set.of(3),List.of(1,0));
        assertEquals(Map.of(0,3,1,3),audit.previousOutlets());
        assertEquals(audit.previousOutlets(),audit.nextOutlets());
        assertTrue(audit.changedVertices().isEmpty());
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRouteMigrationV2.verify(previous,next,Set.of(3),List.of(0,0)));
    }

    @Test void rejectsCyclesUnverifiedMouthsAndUnknownFrontiers() {
        int[] previous = {1,3,3,-1,-1};
        assertThrows(IllegalStateException.class,()->
            HydrologyRouteMigrationV2.verify(previous,
                new int[]{2,3,0,-1,-1},Set.of(3,4),List.of(0)));
        assertThrows(IllegalStateException.class,()->
            HydrologyRouteMigrationV2.verify(previous,
                new int[]{2,3,-1,-1,-1},Set.of(3,4),List.of(0)));
        assertThrows(IllegalStateException.class,()->
            HydrologyRouteMigrationV2.verify(previous,
                new int[]{2,3,99,-1,-1},Set.of(3,4),List.of(0)));
        assertThrows(IllegalStateException.class,()->
            HydrologyRouteMigrationV2.verify(previous,
                new int[]{2,3,-2,-1,-1},Set.of(3,4),List.of(0)));
    }

    @Test void rejectsMismatchedTerrainAndInvalidReceiptOwnership() {
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRouteMigrationV2.verify(new int[]{-1},new int[]{-1,-1},Set.of(0),List.of(0)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRouteMigrationV2.verify(new int[]{-1},new int[]{-1},Set.of(0),List.of(1)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyRouteMigrationV2.verify(new int[]{-1},new int[]{-1},Set.of(0,0),List.of(0)));
    }
}
