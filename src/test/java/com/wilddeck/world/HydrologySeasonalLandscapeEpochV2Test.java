package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HydrologySeasonalLandscapeEpochV2Test {
    @TempDir Path directory;
    private static final double RADIUS = 1000;

    private static List<GlobalHydrologyAddressV2.Sample> window() {
        var result = new ArrayList<GlobalHydrologyAddressV2.Sample>();
        for (int y = 4; y < 9; y++)
            for (int x = 4; x < 9; x++)
                result.add(new GlobalHydrologyAddressV2.Sample(2, 4, x, y));
        return List.copyOf(result);
    }

    private static String initial() {
        var state = new HydrologySeasonalLandscapeStateV2.State("world", RADIUS,
            new HydrologyTerrainOverlayV2.State("world", Map.of()),
            new HydrologySnowSoilStateV2.State("world", Map.of()));
        return HydrologySeasonalLandscapeStateV2.encode(state);
    }

    private static Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing>
            weather(double precipitation) {
        var result = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,
                                       HydrologySnowSoilStateV2.Forcing>();
        for (var address : window())
            result.put(address, HydrologySnowSoilStateV2.Forcing.forSphericalCell(
                address, RADIUS, precipitation, 5, 0, 0, 1, 10));
        return result;
    }

    private static HydrologySeasonalLandscapeEpochV2.Committed advance(
            HydrologyWorldRunnerV2 runner, double precipitation,
            HydrologyGlobalSlopeRouterV2.Elevations elevations) throws IOException {
        var capacity = new double[25];
        Arrays.fill(capacity, 1e12);
        return HydrologySeasonalLandscapeEpochV2.advanceClosedWindow(
            runner, window(), 5, 5, elevations, weather(precipitation),
            RADIUS, 2000, 0.01, 0.05, capacity, Set.of(24));
    }

    private static HydrologyGlobalSlopeRouterV2.Elevations slope() {
        return a -> 20 - (a.x() - 4) - (a.y() - 4);
    }

    @Test void commitsRunoffTerrainAndSnowSoilTogetherAcrossRestarts()
            throws IOException {
        Path path = directory.resolve("seasonal.chk");
        var runner = HydrologyWorldRunnerV2.open(path, 0, initial());
        var first = advance(runner, 2, slope());
        assertEquals(1, runner.epoch());
        assertTrue(first.budget().input() > 0);
        assertEquals(first.budget().input(), first.budget().outlet(), 1e-6);
        assertEquals(0, first.budget().frontier(), 1e-12);
        assertTrue(first.prepared().preview().erosion().terrain()
            .landscape().erodedMass() > 0);
        var saved = HydrologySeasonalLandscapeStateV2.decode(runner.worldState());
        assertEquals(first.prepared().state(), saved);
        assertEquals(25, saved.terrain().elevations().size());
        assertEquals(25, saved.snowSoil().cells().size());

        runner = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        var second = advance(runner, 1, slope());
        assertEquals(2, runner.epoch());
        assertEquals(second.prepared().state(),
            HydrologySeasonalLandscapeStateV2.decode(
                HydrologyWorldRunnerV2.open(path, 0, "ignored").worldState()));
        assertEquals(0, runner.receiptBudget().pendingVolume(), 1e-12);
    }

    @Test void rejectsPondedDepressionWithoutCommittingInstantaneousSpill()
            throws IOException {
        Path path = directory.resolve("pond.chk");
        var runner = HydrologyWorldRunnerV2.open(path, 0, initial());
        var before = HydrologyCheckpointStoreV2.load(path);
        assertThrows(IllegalStateException.class, () -> advance(runner, 2,
            a -> a.x() == 6 && a.y() == 6 ? -100 : 20 - (a.x() - 4) - (a.y() - 4)));
        assertEquals(before, HydrologyCheckpointStoreV2.load(path));
        assertEquals(0, runner.epoch());
    }

    @Test void pendingBoundaryWaterAndInvalidRadiusFailBeforeCommit()
            throws IOException {
        Path path = directory.resolve("pending.chk");
        var runner = HydrologyWorldRunnerV2.open(path, 0, initial());
        var key = new HydrologyTransferLedgerV2.Key(3,
            new GlobalHydrologyAddressV2.Sample(2, 4, 3, 4),
            new GlobalHydrologyAddressV2.Sample(2, 4, 4, 4));
        runner.receive(key, 5);
        var before = HydrologyCheckpointStoreV2.load(path);
        assertThrows(IllegalStateException.class, () -> advance(runner, 2, slope()));
        assertEquals(before, HydrologyCheckpointStoreV2.load(path));
        assertEquals(5, runner.receiptBudget().pendingVolume(), 1e-12);

        var dry = HydrologyWorldRunnerV2.open(directory.resolve("radius.chk"), 0, initial());
        var dryBefore = dry.worldState();
        var capacity = new double[25];
        Arrays.fill(capacity, 1e12);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySeasonalLandscapeEpochV2.advanceClosedWindow(
                dry, window(), 5, 5, slope(), weather(2),
                RADIUS + 1, 2000, 0.01, 0.05, capacity, Set.of(24)));
        assertEquals(0, dry.epoch());
        assertEquals(dryBefore, dry.worldState());
    }

    @Test void staleRunnerCannotOverwriteNewlyDeliveredWater()
            throws IOException {
        Path path = directory.resolve("stale.chk");
        var stale = HydrologyWorldRunnerV2.open(path, 0, initial());
        var writer = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        var key = new HydrologyTransferLedgerV2.Key(3,
            new GlobalHydrologyAddressV2.Sample(2, 4, 3, 4),
            new GlobalHydrologyAddressV2.Sample(2, 4, 4, 4));
        writer.receive(key, 7);
        assertThrows(IOException.class, () -> advance(stale, 2, slope()));
        var recovered = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        assertEquals(0, recovered.epoch());
        assertEquals(7, recovered.receiptBudget().pendingVolume(), 1e-12);
        assertEquals(initial(), recovered.worldState());
    }

    @Test void verifiedExternalExitQueuesOneDurableReceiptAndDeliversOnce()
            throws IOException {
        Path path = directory.resolve("frontier.chk");
        var runner = HydrologyWorldRunnerV2.open(path, 0, initial());
        var capacity = new double[25];
        Arrays.fill(capacity, 1e12);
        var exit = new GlobalHydrologyAddressV2.Sample(2, 4, 9, 9);
        var step = HydrologySeasonalLandscapeEpochV2.advanceVerifiedFrontier(
            runner, window(), 5, 5, slope(), weather(2),
            RADIUS, 2000, 0.01, 0.05, capacity, Set.of(24), Map.of(24, exit));
        assertEquals(1, step.queuedTransfers());
        assertEquals(0, step.budget().outlet(), 1e-12);
        assertEquals(step.budget().input(), step.budget().frontier(), 1e-6);
        assertEquals(step.budget().frontier(),
            runner.receiptBudget().pendingVolume(), 1e-6);
        var storedLandscape = runner.worldState();

        runner = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        var receiver = List.of(new HydrologyFlowGraphV2.Node(exit, 0, null, true));
        var received = runner.advanceAndQueueKnownFrontier(
            storedLandscape, List.of(receiver));
        assertEquals(step.budget().frontier(),
            received.committed().flow().terminalOutflow().get(exit), 1e-6);
        assertEquals(0, runner.receiptBudget().pendingVolume(), 1e-12);
        assertEquals(storedLandscape, runner.worldState());
        var restored = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        assertEquals(1, restored.inboxSnapshot().delivered().size());
        assertEquals(0, restored.advance(storedLandscape, List.of(receiver))
            .terminalOutflow().get(exit), 1e-12);
    }

    @Test void rejectsUnverifiedOrUphillFrontierWithoutChangingWorld()
            throws IOException {
        Path path = directory.resolve("invalid-frontier.chk");
        var runner = HydrologyWorldRunnerV2.open(path, 0, initial());
        var original = HydrologyCheckpointStoreV2.load(path);
        var capacity = new double[25];
        Arrays.fill(capacity, 1e12);
        var flat = new GlobalHydrologyAddressV2.Sample(2, 4, 7, 9);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySeasonalLandscapeEpochV2.advanceVerifiedFrontier(
                runner, window(), 5, 5, slope(), weather(2),
                RADIUS, 2000, 0.01, 0.05, capacity, Set.of(24),
                Map.of(24, flat)));
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySeasonalLandscapeEpochV2.advanceVerifiedFrontier(
                runner, window(), 5, 5, slope(), weather(2),
                RADIUS, 2000, 0.01, 0.05, capacity, Set.of(24),
                Map.of(0, new GlobalHydrologyAddressV2.Sample(2, 4, 3, 4))));
        assertEquals(original, HydrologyCheckpointStoreV2.load(path));
    }

    @Test void frozenSnowWaitsForThawInsteadOfBeingCountedTwice()
            throws IOException {
        Path path = directory.resolve("snow.chk");
        var runner = HydrologyWorldRunnerV2.open(path, 0, initial());
        var capacity = new double[25];
        Arrays.fill(capacity, 1e12);
        var frozen = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,
                                        HydrologySnowSoilStateV2.Forcing>();
        var thawed = new LinkedHashMap<GlobalHydrologyAddressV2.Sample,
                                        HydrologySnowSoilStateV2.Forcing>();
        for (var address : window()) {
            frozen.put(address, HydrologySnowSoilStateV2.Forcing.forSphericalCell(
                address, RADIUS, 2, -5, 0, 0, 1, 10));
            thawed.put(address, HydrologySnowSoilStateV2.Forcing.forSphericalCell(
                address, RADIUS, 0, 5, 0, 0, 1, 10));
        }
        var winter = HydrologySeasonalLandscapeEpochV2.advanceClosedWindow(
            runner, window(), 5, 5, slope(), frozen, RADIUS, 2000,
            0.01, 0.05, capacity, Set.of(24));
        assertEquals(0, winter.budget().input(), 1e-12);
        assertTrue(winter.prepared().preview().season().storedVolume() > 0);
        runner = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        var spring = HydrologySeasonalLandscapeEpochV2.advanceClosedWindow(
            runner, window(), 5, 5, slope(), thawed, RADIUS, 2000,
            0.01, 0.05, capacity, Set.of(24));
        assertEquals(winter.prepared().preview().season().storedVolume(),
            spring.budget().outlet(), 1e-6);
        assertEquals(0, spring.prepared().preview().season().storedVolume(), 1e-12);
        assertEquals(2, runner.epoch());
    }

    @Test void verifiedOutletReceiptsJoinSeasonalRunoffAndExportExactlyOnce()
            throws IOException {
        Path path = directory.resolve("incoming-outlet.chk");
        var runner = HydrologyWorldRunnerV2.open(path, 0, initial());
        var outlet = window().get(24);
        var west = new GlobalHydrologyAddressV2.Sample(2, 4, 9, 8);
        var north = new GlobalHydrologyAddressV2.Sample(2, 4, 8, 9);
        var exit = new GlobalHydrologyAddressV2.Sample(2, 4, 9, 9);
        var first = new HydrologyTransferLedgerV2.Key(1, west, outlet);
        var second = new HydrologyTransferLedgerV2.Key(1, north, outlet);
        assertTrue(runner.receive(first, 3));
        assertFalse(runner.receive(first, 3)); // idempotent old receipt
        assertTrue(runner.receive(second, 4));
        HydrologyGlobalSlopeRouterV2.Elevations terrain = a ->
            a.equals(west) || a.equals(north) ? 100 :
            a.equals(exit) ? -10 : slope().at(a);
        var capacity = new double[25];
        Arrays.fill(capacity, 1e12);
        var step = HydrologySeasonalLandscapeEpochV2.advanceWithVerifiedOutletReceipts(
            runner, window(), 5, 5, terrain, weather(2),
            RADIUS, 2000, 0.01, 0.05, capacity, Set.of(24), Map.of(24, exit));
        assertEquals(1, runner.epoch());
        assertEquals(1, step.queuedTransfers());
        assertEquals(step.prepared().preview().erosion().runoffVolume() + 7,
            step.budget().input(), 1e-6);
        assertEquals(step.budget().input(), step.budget().frontier(), 1e-6);
        assertEquals(2, runner.inboxSnapshot().delivered().size());
        assertEquals(step.budget().frontier(),
            runner.receiptBudget().pendingVolume(), 1e-6);
        var storedLandscape = runner.worldState();

        runner = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        var receiver = List.of(new HydrologyFlowGraphV2.Node(exit, 0, null, true));
        var received = runner.advanceAndQueueKnownFrontier(
            storedLandscape, List.of(receiver));
        assertEquals(step.budget().frontier(),
            received.committed().flow().terminalOutflow().get(exit), 1e-6);
        assertEquals(0, runner.receiptBudget().pendingVolume(), 1e-12);
        runner = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        assertEquals(0, runner.advance(storedLandscape, List.of(receiver))
            .terminalOutflow().get(exit), 1e-12);
    }

    @Test void outletReceiptGateRejectsInteriorFutureAndUphillWater()
            throws IOException {
        var capacity = new double[25];
        Arrays.fill(capacity, 1e12);
        var outlet = window().get(24);
        var source = new GlobalHydrologyAddressV2.Sample(2, 4, 9, 8);
        var steep = (HydrologyGlobalSlopeRouterV2.Elevations) a ->
            a.equals(source) ? 100 : slope().at(a);
        var interior = HydrologyWorldRunnerV2.open(
            directory.resolve("interior.chk"), 0, initial());
        interior.receive(new HydrologyTransferLedgerV2.Key(1,
            new GlobalHydrologyAddressV2.Sample(2, 4, 3, 4), window().get(0)), 3);
        var before = interior.worldState();
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySeasonalLandscapeEpochV2.advanceWithVerifiedOutletReceipts(
                interior, window(), 5, 5, slope(), weather(2),
                RADIUS, 2000, 0.01, 0.05, capacity, Set.of(24), Map.of()));
        assertEquals(before, interior.worldState());
        assertEquals(3, interior.receiptBudget().pendingVolume(), 1e-12);

        var future = HydrologyWorldRunnerV2.open(
            directory.resolve("future-outlet.chk"), 0, initial());
        future.receive(new HydrologyTransferLedgerV2.Key(3, source, outlet), 5);
        assertThrows(IllegalStateException.class, () ->
            HydrologySeasonalLandscapeEpochV2.advanceWithVerifiedOutletReceipts(
                future, window(), 5, 5, steep, weather(2),
                RADIUS, 2000, 0.01, 0.05, capacity, Set.of(24), Map.of()));
        assertEquals(0, future.epoch());
        assertEquals(5, future.receiptBudget().pendingVolume(), 1e-12);

        var uphill = HydrologyWorldRunnerV2.open(
            directory.resolve("uphill-outlet.chk"), 0, initial());
        uphill.receive(new HydrologyTransferLedgerV2.Key(1, source, outlet), 7);
        assertThrows(IllegalArgumentException.class, () ->
            HydrologySeasonalLandscapeEpochV2.advanceWithVerifiedOutletReceipts(
                uphill, window(), 5, 5, slope(), weather(2),
                RADIUS, 2000, 0.01, 0.05, capacity, Set.of(24), Map.of()));
        assertEquals(0, uphill.epoch());
        assertEquals(7, uphill.receiptBudget().pendingVolume(), 1e-12);
    }

    @Test void importedOutletWaterDoesNotReErodeInteriorCells() throws IOException {
        var dry = HydrologyWorldRunnerV2.open(
            directory.resolve("dry-erosion.chk"), 0, initial());
        var wet = HydrologyWorldRunnerV2.open(
            directory.resolve("wet-erosion.chk"), 0, initial());
        var outlet = window().get(24);
        var source = new GlobalHydrologyAddressV2.Sample(2, 4, 9, 8);
        var elevations = (HydrologyGlobalSlopeRouterV2.Elevations) a ->
            a.equals(source) ? 100 : slope().at(a);
        wet.receive(new HydrologyTransferLedgerV2.Key(1, source, outlet), 7);
        var capacity = new double[25];
        Arrays.fill(capacity, 1e12);
        var dryStep = HydrologySeasonalLandscapeEpochV2.advanceClosedWindow(
            dry, window(), 5, 5, elevations, weather(2),
            RADIUS, 2000, 0.01, 0.05, capacity, Set.of(24));
        var wetStep = HydrologySeasonalLandscapeEpochV2.advanceWithVerifiedOutletReceipts(
            wet, window(), 5, 5, elevations, weather(2),
            RADIUS, 2000, 0.01, 0.05, capacity, Set.of(24), Map.of());
        assertEquals(dryStep.prepared().state().terrain(),
            wetStep.prepared().state().terrain());
        assertEquals(dryStep.prepared().state().snowSoil(),
            wetStep.prepared().state().snowSoil());
        assertEquals(dryStep.budget().outlet() + 7,
            wetStep.budget().outlet(), 1e-6);
        assertEquals(0, wet.receiptBudget().pendingVolume(), 1e-12);
        assertEquals(1, wet.inboxSnapshot().delivered().size());
    }

    @Test void importedInteriorWaterDrivesErosionAndRoutesExactlyOnce()
            throws IOException {
        Path path=directory.resolve("interior-reconciled.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,initial());
        var source=new GlobalHydrologyAddressV2.Sample(2,4,3,4);
        var landing=window().get(0);
        var key=new HydrologyTransferLedgerV2.Key(1,source,landing);
        assertTrue(runner.receive(key,1000));
        assertFalse(runner.receive(key,1000));
        var heights=(HydrologyGlobalSlopeRouterV2.Elevations) a ->
            a.equals(source) ? 100 : slope().at(a);
        var capacity=new double[25];
        Arrays.fill(capacity,1e12);
        var wet=HydrologySeasonalLandscapeEpochV2.advanceWithVerifiedInteriorReceipts(
            runner,window(),5,5,heights,weather(0),RADIUS,2000,
            0.01,0.05,capacity,Set.of(24),Map.of());
        assertEquals(1,runner.epoch());
        assertEquals(1000,wet.budget().input(),1e-6);
        assertEquals(1000,wet.budget().outlet(),1e-6);
        assertEquals(1000,wet.prepared().preview().erosion().terminalVolume(),1e-6);
        assertEquals(0,wet.prepared().preview().erosion().runoffVolume(),0);
        assertTrue(wet.prepared().preview().erosion().terrain()
            .landscape().erodedMass()>0);
        assertEquals(0,runner.receiptBudget().pendingVolume(),1e-12);
        assertEquals(1,runner.inboxSnapshot().delivered().size());
        var saved=runner.worldState();
        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(saved,runner.worldState());
        assertEquals(0,runner.receiptBudget().pendingVolume(),1e-12);
        assertEquals(0,runner.advance(saved,
            List.of(List.of(new HydrologyFlowGraphV2.Node(landing,0,null,true))))
            .terminalOutflow().get(landing),1e-12);
    }

    @Test void importedInteriorWaterExportsThroughUnloadedBoundaryAfterRestart()
            throws IOException {
        Path path=directory.resolve("interior-frontier.chk");
        var runner=HydrologyWorldRunnerV2.open(path,0,initial());
        var source=new GlobalHydrologyAddressV2.Sample(2,4,3,4);
        var landing=window().get(0);
        var exit=new GlobalHydrologyAddressV2.Sample(2,4,9,9);
        runner.receive(new HydrologyTransferLedgerV2.Key(1,source,landing),250);
        var heights=(HydrologyGlobalSlopeRouterV2.Elevations) a ->
            a.equals(source) ? 100 : a.equals(exit) ? -10 : slope().at(a);
        var capacity=new double[25];
        Arrays.fill(capacity,1e12);
        var step=HydrologySeasonalLandscapeEpochV2.advanceWithVerifiedInteriorReceipts(
            runner,window(),5,5,heights,weather(0),RADIUS,2000,
            0.01,0.05,capacity,Set.of(24),Map.of(24,exit));
        assertEquals(250,step.budget().input(),1e-6);
        assertEquals(250,step.budget().frontier(),1e-6);
        assertEquals(1,step.queuedTransfers());
        assertEquals(250,runner.receiptBudget().pendingVolume(),1e-6);
        var saved=runner.worldState();
        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        var receiver=List.of(new HydrologyFlowGraphV2.Node(exit,0,null,true));
        var delivered=runner.advanceAndQueueKnownFrontier(
            saved,List.of(receiver));
        assertEquals(250,
            delivered.committed().flow().terminalOutflow().get(exit),1e-6);
        assertEquals(0,runner.receiptBudget().pendingVolume(),1e-12);
        runner=HydrologyWorldRunnerV2.open(path,0,"ignored");
        assertEquals(0,runner.advance(saved,List.of(receiver))
            .terminalOutflow().get(exit),1e-12);
    }

    @Test void interiorReceiptRejectsFutureAndUphillEdgesWithoutConsumption()
            throws IOException {
        var source=new GlobalHydrologyAddressV2.Sample(2,4,3,4);
        var landing=window().get(0);
        var capacity=new double[25];
        Arrays.fill(capacity,1e12);
        var future=HydrologyWorldRunnerV2.open(
            directory.resolve("interior-future.chk"),0,initial());
        future.receive(new HydrologyTransferLedgerV2.Key(3,source,landing),10);
        assertThrows(IllegalStateException.class,()->
            HydrologySeasonalLandscapeEpochV2.advanceWithVerifiedInteriorReceipts(
                future,window(),5,5,slope(),weather(0),RADIUS,2000,
                0.01,0.05,capacity,Set.of(24),Map.of()));
        assertEquals(0,future.epoch());
        assertEquals(10,future.receiptBudget().pendingVolume(),1e-12);
        var uphill=HydrologyWorldRunnerV2.open(
            directory.resolve("interior-uphill.chk"),0,initial());
        uphill.receive(new HydrologyTransferLedgerV2.Key(1,source,landing),12);
        assertThrows(IllegalArgumentException.class,()->
            HydrologySeasonalLandscapeEpochV2.advanceWithVerifiedInteriorReceipts(
                uphill,window(),5,5,
                a -> a.equals(source) ? 0 : slope().at(a),
                weather(0),RADIUS,2000,
                0.01,0.05,capacity,Set.of(24),Map.of()));
        assertEquals(0,uphill.epoch());
        assertEquals(12,uphill.receiptBudget().pendingVolume(),1e-12);
    }

    @Test void receiptArrivingDuringSeasonalPreparationCannotBeOverwritten()
            throws IOException {
        Path path = directory.resolve("concurrent-outlet.chk");
        var runner = HydrologyWorldRunnerV2.open(path, 0, initial());
        var outlet = window().get(24);
        var firstSource = new GlobalHydrologyAddressV2.Sample(2, 4, 9, 8);
        var secondSource = new GlobalHydrologyAddressV2.Sample(2, 4, 8, 9);
        runner.receive(new HydrologyTransferLedgerV2.Key(
            1, firstSource, outlet), 3);
        var inserted = new java.util.concurrent.atomic.AtomicBoolean();
        HydrologyGlobalSlopeRouterV2.Elevations reentrant = a -> {
            if (inserted.compareAndSet(false, true)) {
                try {
                    runner.receive(new HydrologyTransferLedgerV2.Key(
                        1, secondSource, outlet), 4);
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            }
            return a.equals(firstSource) || a.equals(secondSource)
                ? 100 : slope().at(a);
        };
        var capacity = new double[25];
        Arrays.fill(capacity, 1e12);
        assertThrows(IOException.class, () ->
            HydrologySeasonalLandscapeEpochV2.advanceWithVerifiedOutletReceipts(
                runner, window(), 5, 5, reentrant, weather(2),
                RADIUS, 2000, 0.01, 0.05, capacity, Set.of(24), Map.of()));
        assertTrue(inserted.get());
        var restored = HydrologyWorldRunnerV2.open(path, 0, "ignored");
        assertEquals(0, restored.epoch());
        assertEquals(initial(), restored.worldState());
        assertEquals(7, restored.receiptBudget().pendingVolume(), 1e-12);
        assertTrue(restored.inboxSnapshot().delivered().isEmpty());
    }
}
