package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalSedimentCandidateV2Test {
    private static final double RADIUS = 1000;

    private static List<GlobalHydrologyAddressV2.Sample> window() {
        var samples = new ArrayList<GlobalHydrologyAddressV2.Sample>();
        for (int y = 4; y < 9; y++)
            for (int x = 4; x < 9; x++)
                samples.add(new GlobalHydrologyAddressV2.Sample(2, 4, x, y));
        return List.copyOf(samples);
    }

    private static HydrologyGlobalSlopeRouterV2.Elevations seed() {
        return address -> 20 - (address.x() - 4) - (address.y() - 4);
    }

    private static Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing>
            weather() {
        var result = new HashMap<GlobalHydrologyAddressV2.Sample,
                                 HydrologySnowSoilStateV2.Forcing>();
        for (var address : window())
            result.put(address, HydrologySnowSoilStateV2.Forcing.forSphericalCell(
                address, RADIUS, 2, 5, 0, 0, 1, 10));
        return result;
    }

    private static double[] capacity() {
        var values = new double[25];
        Arrays.fill(values, 2_000_000);
        return values;
    }

    private static HydrologySedimentReceiptInboxV2.Receipt receipt(long arrival) {
        return new HydrologySedimentReceiptInboxV2.Receipt(0, arrival,
            new GlobalHydrologyAddressV2.Sample(2,4,3,4),
            new GlobalHydrologyAddressV2.Sample(2,4,4,4));
    }

    private static HydrologyCheckpointV2.Checkpoint checkpoint(
            HydrologySedimentReceiptInboxV2.Snapshot sediment) {
        var landscape = new HydrologySeasonalLandscapeStateV2.State(
            "world", RADIUS,
            new HydrologyTerrainOverlayV2.State("world", Map.of()),
            new HydrologySnowSoilStateV2.State("world", Map.of()));
        return HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.encode(
                HydrologySeasonalLandscapeStateV2.encode(landscape), sediment),
            new HydrologyEpochInboxV2());
    }

    private static HydrologySeasonalSedimentCandidateV2.Candidate stage(
            HydrologyCheckpointV2.Checkpoint checkpoint) {
        return HydrologySeasonalSedimentCandidateV2.stage(
            checkpoint, window(), 5, 5, seed(), weather(), RADIUS,
            2000, .1, .4, capacity(), Set.of(24), Map.of());
    }

    @Test void stagesImportedMassWithSnowTerrainAndReceiptMarkersInOnePayload() {
        var received = HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(), receipt(1), 100);
        var before = checkpoint(received);
        var first = stage(before);
        var again = stage(before);
        assertEquals(first.next(), again.next());
        assertEquals(0, before.epoch());
        assertEquals(1, first.next().epoch());
        assertEquals(before.inboxState(), first.next().inboxState());
        assertEquals(100, first.claimed().importedMass(), 0);
        assertTrue(first.claimed().snapshot().delivered().contains(receipt(1)));
        assertEquals(100, first.landscape().preview().erosion().terrain()
            .landscape().importedMass(), 0);
        var restored = HydrologySedimentCheckpointV2.decode(first.next().worldState());
        assertEquals(first.claimed().snapshot(), restored.sediment());
        assertEquals(first.landscape().state(),
            HydrologySeasonalLandscapeStateV2.decode(restored.world()));
        assertFalse(HydrologySedimentCheckpointV2.decode(before.worldState())
            .sediment().delivered().contains(receipt(1)));
    }

    @Test void leavesFutureUnloadedReceiptsPendingWithoutAddingMass() {
        var received = HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(), receipt(3), 75);
        var result = stage(checkpoint(received));
        assertEquals(0, result.claimed().importedMass(), 0);
        assertTrue(result.claimed().snapshot().delivered().isEmpty());
        assertTrue(result.claimed().snapshot().receipts().containsKey(receipt(3)));
        assertEquals(1, result.claimed().snapshot().lastClaimedEpoch());
    }

    @Test void rejectsForgedEpochAndInteriorSedimentSource() {
        assertThrows(IllegalArgumentException.class, () ->
            stage(checkpoint(new HydrologySedimentReceiptInboxV2.Snapshot(
                Map.of(), Set.of(), 2))));
        var inside = new HydrologySedimentReceiptInboxV2.Receipt(0,1,
            new GlobalHydrologyAddressV2.Sample(2,4,5,4),
            new GlobalHydrologyAddressV2.Sample(2,4,4,4));
        var received = HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(), inside, 50);
        assertThrows(IllegalArgumentException.class, () ->
            stage(checkpoint(received)));
    }

    @Test void rejectsCrossResolutionAliasAsAnExternalSedimentSource() {
        var alias = new HydrologySedimentReceiptInboxV2.Receipt(0,1,
            new GlobalHydrologyAddressV2.Sample(1,4,2,2),
            new GlobalHydrologyAddressV2.Sample(1,4,3,2));
        var received = HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(),alias,30);
        assertThrows(IllegalArgumentException.class, () ->
            stage(checkpoint(received)));
    }

    @Test void rejectsPendingWaterInsteadOfSilentlyDroppingBoundaryReceipts() {
        var empty = checkpoint(HydrologySedimentReceiptInboxV2.empty());
        var water = new HydrologyEpochInboxV2();
        water.receive(new HydrologyTransferLedgerV2.Key(1,
            new GlobalHydrologyAddressV2.Sample(2,4,3,4),
            new GlobalHydrologyAddressV2.Sample(2,4,4,4)), 25);
        var withWater = HydrologyCheckpointV2.capture(0,empty.worldState(),water);
        assertThrows(IllegalStateException.class, () -> stage(withWater));
    }

    @Test void rejectsDuplicatePhysicalWindowAndWrongPlanetRadius() {
        var before = checkpoint(HydrologySedimentReceiptInboxV2.empty());
        var duplicate = new ArrayList<>(window());
        duplicate.set(1, duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySeasonalSedimentCandidateV2.stage(before, duplicate,5,5,
                seed(),weather(),RADIUS,2000,.1,.4,capacity(),Set.of(24),Map.of()));
        var old = HydrologySeasonalLandscapeStateV2.decode(
            HydrologySedimentCheckpointV2.decode(before.worldState()).world());
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySeasonalLandscapeStateV2.prepareWithIncoming(old,
                window(),5,5,seed(),weather(),RADIUS+1,2000,.1,.4,
                capacity(),Set.of(24),Map.of(),Map.of()));
    }
}
