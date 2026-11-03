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

    @Test void keepsImportedWaterSeparateFromPrecipitationAndSediment() {
        var received = HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(),receipt(1),100);
        var landing = window().get(0);
        var candidate = HydrologySeasonalSedimentCandidateV2.stage(
            checkpoint(received),window(),5,5,seed(),weather(),RADIUS,
            2000,.1,.4,capacity(),Set.of(24),Map.of(landing,20.0));
        var preview = candidate.landscape().preview();
        assertEquals(100,candidate.claimed().importedMass(),0);
        assertEquals(preview.erosion().runoffVolume()+20,
            preview.erosion().terminalVolume(),1e-6);
        var unimported = stage(checkpoint(received)).landscape().preview();
        assertEquals(unimported.season().precipitationVolume(),
            preview.season().precipitationVolume(),0);
        assertEquals(unimported.season().runoffVolume(),
            preview.season().runoffVolume(),0);
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

    private static HydrologySeasonalSedimentCandidateV2.Candidate coupled(
            HydrologyCheckpointV2.Checkpoint checkpoint) {
        return HydrologySeasonalSedimentCandidateV2.stageWithWaterReceipts(
            checkpoint, window(), 5, 5, seed(), weather(), RADIUS,
            2000, .1, .4, capacity(), Set.of(24));
    }

    private static HydrologyTransferLedgerV2.Key water(long epoch) {
        return new HydrologyTransferLedgerV2.Key(epoch,
            new GlobalHydrologyAddressV2.Sample(2,4,3,4),
            new GlobalHydrologyAddressV2.Sample(2,4,4,4));
    }

    @Test void stagesBothReceiptKindsInOneDeterministicEpoch() {
        var sediment = HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(), receipt(1), 100);
        var original = checkpoint(sediment);
        var inbox = new HydrologyEpochInboxV2();
        inbox.receive(water(1), 20);
        var before = new HydrologyCheckpointV2.Checkpoint(
            0, original.worldState(), HydrologyInboxCodecV2.encode(inbox.snapshot()));
        var first = coupled(before);
        assertEquals(first.next(), coupled(before).next());
        assertEquals(1, first.next().epoch());
        var settledWater = HydrologyInboxCodecV2.decode(first.next().inboxState());
        assertTrue(settledWater.delivered().contains(water(1)));
        assertTrue(settledWater.consumedEpochs().contains(1L));
        var settledSediment = HydrologySedimentCheckpointV2.decode(
            first.next().worldState()).sediment();
        assertTrue(settledSediment.delivered().contains(receipt(1)));
        assertEquals(100, first.claimed().importedMass(), 0);
        assertEquals(first.landscape().preview().erosion().runoffVolume() + 20,
            first.landscape().preview().erosion().terminalVolume(), 1e-6);
        assertFalse(HydrologyInboxCodecV2.decode(before.inboxState())
            .delivered().contains(water(1)));
        assertFalse(sediment.delivered().contains(receipt(1)));
    }

    @Test void futureWaterAndSedimentRemainPendingWithOriginalIdentities() {
        var sediment = HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(), receipt(3), 70);
        var original = checkpoint(sediment);
        var inbox = new HydrologyEpochInboxV2();
        inbox.receive(water(3), 15);
        var before = new HydrologyCheckpointV2.Checkpoint(
            0, original.worldState(), HydrologyInboxCodecV2.encode(inbox.snapshot()));
        var next = coupled(before).next();
        var waterState = HydrologyInboxCodecV2.decode(next.inboxState());
        var sedimentState = HydrologySedimentCheckpointV2.decode(next.worldState()).sediment();
        assertEquals(15, waterState.receipts().get(water(3)), 0);
        assertFalse(waterState.delivered().contains(water(3)));
        assertTrue(waterState.consumedEpochs().contains(1L));
        assertEquals(70, sedimentState.receipts().get(receipt(3)), 0);
        assertFalse(sedimentState.delivered().contains(receipt(3)));
    }

    @Test void rejectsInteriorWaterSourceWithoutConsumingEitherReceipt() {
        var sediment = HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(), receipt(1), 100);
        var original = checkpoint(sediment);
        var inbox = new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(1,
            new GlobalHydrologyAddressV2.Sample(2,4,5,4),
            new GlobalHydrologyAddressV2.Sample(2,4,4,4)), 20);
        var before = new HydrologyCheckpointV2.Checkpoint(
            0, original.worldState(), HydrologyInboxCodecV2.encode(inbox.snapshot()));
        assertThrows(IllegalArgumentException.class, () -> coupled(before));
        assertFalse(HydrologyInboxCodecV2.decode(before.inboxState())
            .delivered().containsAll(inbox.snapshot().receipts().keySet()));
        assertFalse(sediment.delivered().contains(receipt(1)));
    }

    @Test void rejectsCrossResolutionWaterLandingRatherThanStrandingDueReceipt() {
        var original = checkpoint(HydrologySedimentReceiptInboxV2.empty());
        var inbox = new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(1,
            new GlobalHydrologyAddressV2.Sample(1,4,1,2),
            new GlobalHydrologyAddressV2.Sample(1,4,2,2)), 20);
        var before = new HydrologyCheckpointV2.Checkpoint(
            0, original.worldState(), HydrologyInboxCodecV2.encode(inbox.snapshot()));
        assertThrows(IllegalArgumentException.class, () -> coupled(before));
        assertTrue(inbox.snapshot().delivered().isEmpty());
    }

    @Test void rejectsForkedWaterDischargeFromSameSourceEpoch() {
        var original = checkpoint(HydrologySedimentReceiptInboxV2.empty());
        var inbox = new HydrologyEpochInboxV2();
        inbox.receive(water(1), 20);
        inbox.receive(new HydrologyTransferLedgerV2.Key(1,
            new GlobalHydrologyAddressV2.Sample(2,4,3,4),
            new GlobalHydrologyAddressV2.Sample(2,4,4,5)), 30);
        var before = new HydrologyCheckpointV2.Checkpoint(
            0, original.worldState(), HydrologyInboxCodecV2.encode(inbox.snapshot()));
        assertThrows(IllegalArgumentException.class, () -> coupled(before));
        assertTrue(inbox.snapshot().delivered().isEmpty());
    }

    @Test void rejectsCrossResolutionAliasOfWaterSender() {
        var original = checkpoint(HydrologySedimentReceiptInboxV2.empty());
        var inbox = new HydrologyEpochInboxV2();
        inbox.receive(new HydrologyTransferLedgerV2.Key(1,
            new GlobalHydrologyAddressV2.Sample(2,4,2,4),
            new GlobalHydrologyAddressV2.Sample(2,4,3,4)), 20);
        inbox.receive(new HydrologyTransferLedgerV2.Key(1,
            new GlobalHydrologyAddressV2.Sample(1,4,1,2),
            new GlobalHydrologyAddressV2.Sample(1,4,1,3)), 30);
        var before = new HydrologyCheckpointV2.Checkpoint(
            0, original.worldState(), HydrologyInboxCodecV2.encode(inbox.snapshot()));
        assertThrows(IllegalArgumentException.class, () -> coupled(before));
        assertTrue(inbox.snapshot().delivered().isEmpty());
    }

    @Test void futureInteriorWaterCannotBeDeferredIntoAnUnsafeTerrainEpoch() {
        var original = checkpoint(HydrologySedimentReceiptInboxV2.empty());
        var inbox = new HydrologyEpochInboxV2();
        var future = new HydrologyTransferLedgerV2.Key(3,
            new GlobalHydrologyAddressV2.Sample(2,4,5,4),
            new GlobalHydrologyAddressV2.Sample(2,4,4,4));
        inbox.receive(future, 20);
        var before = new HydrologyCheckpointV2.Checkpoint(
            0, original.worldState(), HydrologyInboxCodecV2.encode(inbox.snapshot()));
        assertThrows(IllegalArgumentException.class, () -> coupled(before));
        assertFalse(HydrologyInboxCodecV2.decode(before.inboxState())
            .delivered().contains(future));
    }

    @Test void futureInteriorSedimentCannotBypassExternalSourceOwnership() {
        var future = new HydrologySedimentReceiptInboxV2.Receipt(0,3,
            new GlobalHydrologyAddressV2.Sample(2,4,5,4),
            new GlobalHydrologyAddressV2.Sample(2,4,4,4));
        var received = HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(), future, 50);
        var before = checkpoint(received);
        assertThrows(IllegalArgumentException.class, () -> coupled(before));
        assertFalse(HydrologySedimentCheckpointV2.decode(before.worldState())
            .sediment().delivered().contains(future));
    }

    @Test void futureCrossResolutionSedimentLandingRequiresExplicitMigration() {
        var future = new HydrologySedimentReceiptInboxV2.Receipt(0,3,
            new GlobalHydrologyAddressV2.Sample(1,4,1,2),
            new GlobalHydrologyAddressV2.Sample(1,4,2,2));
        var received = HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(), future, 50);
        assertThrows(IllegalArgumentException.class, () -> coupled(checkpoint(received)));
    }

    @Test void futureCrossResolutionWaterLandingRequiresExplicitMigration() {
        var original = checkpoint(HydrologySedimentReceiptInboxV2.empty());
        var inbox = new HydrologyEpochInboxV2();
        var future = new HydrologyTransferLedgerV2.Key(3,
            new GlobalHydrologyAddressV2.Sample(1,4,1,2),
            new GlobalHydrologyAddressV2.Sample(1,4,2,2));
        inbox.receive(future, 20);
        var before = new HydrologyCheckpointV2.Checkpoint(
            0, original.worldState(), HydrologyInboxCodecV2.encode(inbox.snapshot()));
        assertThrows(IllegalArgumentException.class, () -> coupled(before));
    }

    @Test void independentlyAuditsWaterAndSedimentImportsWithoutRainfallDoubleCount() {
        var sediment = HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(), receipt(1), 100);
        var original = checkpoint(sediment);
        var inbox = new HydrologyEpochInboxV2();
        inbox.receive(water(1), 20);
        var before = new HydrologyCheckpointV2.Checkpoint(
            0, original.worldState(), HydrologyInboxCodecV2.encode(inbox.snapshot()));
        var candidate = coupled(before);
        var audit = HydrologySeasonalReceiptAuditV2.verify(before, candidate, window());
        assertEquals(1, audit.epoch());
        assertEquals(0, audit.waterCubicMeters().compareTo(
            java.math.BigDecimal.valueOf(20)));
        assertEquals(0, audit.sedimentKilograms().compareTo(
            java.math.BigDecimal.valueOf(100)));
        assertEquals(1, audit.waterReceipts());
        assertEquals(1, audit.sedimentReceipts());
    }

    @Test void independentAuditRejectsMutatedWaterReceiptAmounts() {
        var original = checkpoint(HydrologySedimentReceiptInboxV2.empty());
        var inbox = new HydrologyEpochInboxV2();
        inbox.receive(water(1), 20);
        var before = new HydrologyCheckpointV2.Checkpoint(
            0, original.worldState(), HydrologyInboxCodecV2.encode(inbox.snapshot()));
        var candidate = coupled(before);
        var waterState = HydrologyInboxCodecV2.decode(candidate.next().inboxState());
        var forged = new HydrologyEpochInboxV2.Snapshot(
            Map.of(water(1), 999.0), waterState.consumedEpochs(), waterState.delivered());
        var altered = new HydrologyCheckpointV2.Checkpoint(
            candidate.next().epoch(), candidate.next().worldState(),
            HydrologyInboxCodecV2.encode(forged));
        var tampered = new HydrologySeasonalSedimentCandidateV2.Candidate(
            altered, candidate.landscape(), candidate.claimed());
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySeasonalReceiptAuditV2.verify(before, tampered, window()));
    }

    @Test void independentAuditRejectsForgedSedimentMassBudget() {
        var sediment = HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(), receipt(1), 100);
        var before = checkpoint(sediment);
        var candidate = coupled(before);
        var forgedClaim = new HydrologySedimentReceiptInboxV2.Claimed(
            candidate.claimed().snapshot(), Map.of(), 0);
        var tampered = new HydrologySeasonalSedimentCandidateV2.Candidate(
            candidate.next(), candidate.landscape(), forgedClaim);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySeasonalReceiptAuditV2.verify(before, tampered, window()));
    }

    @Test void rejectsCheckpointThatClaimsFutureWaterAlreadyDelivered() {
        var original = checkpoint(HydrologySedimentReceiptInboxV2.empty());
        var future = water(3);
        var forged = new HydrologyEpochInboxV2.Snapshot(
            Map.of(future, 20.0), Set.of(3L), Set.of(future));
        var before = new HydrologyCheckpointV2.Checkpoint(
            0, original.worldState(), HydrologyInboxCodecV2.encode(forged));
        assertThrows(IllegalArgumentException.class, () -> coupled(before));
    }

    @Test void unloadedWaterAndSedimentRemainPendingAcrossCoupledSeason() {
        var source = new GlobalHydrologyAddressV2.Sample(2,4,2,4);
        var destination = new GlobalHydrologyAddressV2.Sample(2,4,3,4);
        var waterReceipt = new HydrologyTransferLedgerV2.Key(3, source, destination);
        var sedimentReceipt = new HydrologySedimentReceiptInboxV2.Receipt(
            0, 3, source, destination);
        var sediment = HydrologySedimentReceiptInboxV2.receive(
            HydrologySedimentReceiptInboxV2.empty(), sedimentReceipt, 40);
        var original = checkpoint(sediment);
        var inbox = new HydrologyEpochInboxV2();
        inbox.receive(waterReceipt, 15);
        var before = new HydrologyCheckpointV2.Checkpoint(
            0, original.worldState(), HydrologyInboxCodecV2.encode(inbox.snapshot()));
        var candidate = coupled(before);
        var waterAfter = HydrologyInboxCodecV2.decode(candidate.next().inboxState());
        var sedimentAfter = HydrologySedimentCheckpointV2.decode(
            candidate.next().worldState()).sediment();
        assertEquals(15, waterAfter.receipts().get(waterReceipt), 0);
        assertFalse(waterAfter.delivered().contains(waterReceipt));
        assertEquals(40, sedimentAfter.receipts().get(sedimentReceipt), 0);
        assertFalse(sedimentAfter.delivered().contains(sedimentReceipt));
        var audit = HydrologySeasonalReceiptAuditV2.verify(before, candidate, window());
        assertEquals(0, audit.waterReceipts());
        assertEquals(0, audit.sedimentReceipts());
    }
}
