package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Durable local coupling of incoming water and sediment receipt histories. */
class HydrologyCoupledReceiptBatchV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(2,4,x,y);
    }
    private static HydrologyTransferLedgerV2.Key water(long epoch,long x,long y,
                                                        long tx,long ty) {
        return new HydrologyTransferLedgerV2.Key(epoch,at(x,y),at(tx,ty));
    }
    private static HydrologySedimentReceiptInboxV2.Receipt sediment(
            long source,long arrival,long x,long y,long tx,long ty) {
        return new HydrologySedimentReceiptInboxV2.Receipt(
            source,arrival,at(x,y),at(tx,ty));
    }
    private static HydrologyCheckpointV2.Checkpoint initial(Path path)
            throws IOException {
        var checkpoint = HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld("world"),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(path,checkpoint);
        return checkpoint;
    }

    @Test void persistsBothKindsInOneCheckpointAndReplaysAfterRestart()
            throws IOException {
        var path = directory.resolve("coupled.chk");
        var before = initial(path);
        var w = water(1,4,4,5,4);
        var s = sediment(0,1,4,4,5,4);
        var result = HydrologyCoupledReceiptBatchV2.receive(
            path,before,Map.of(w,7.0),Map.of(s,3.0));
        assertEquals(1,result.waterAdded());
        assertEquals(1,result.sedimentAdded());
        assertEquals(result.checkpoint(),HydrologyCheckpointStoreV2.load(path));
        assertEquals(7.0,HydrologyInboxCodecV2.decode(
            result.checkpoint().inboxState()).receipts().get(w),0);
        assertEquals(3.0,HydrologySedimentCheckpointV2.decode(
            result.checkpoint().worldState()).sediment().receipts().get(s),0);
        byte[] bytes = Files.readAllBytes(path);
        var replay = HydrologyCoupledReceiptBatchV2.receive(path,
            HydrologyCheckpointStoreV2.load(path),Map.of(w,7.0),Map.of(s,3.0));
        assertEquals(0,replay.waterAdded());
        assertEquals(0,replay.sedimentAdded());
        assertArrayEquals(bytes,Files.readAllBytes(path));
    }

    @Test void invalidSedimentSourceForkRollsBackValidWater()
            throws IOException {
        var path = directory.resolve("sediment-fork.chk");
        var before = initial(path);
        var w = water(1,4,4,5,4);
        var a = sediment(0,1,4,4,5,4);
        var b = sediment(0,2,4,4,4,5);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,before,
                Map.of(w,5.0),Map.of(a,2.0,b,3.0)));
        assertEquals(before,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void invalidWaterSourceForkRollsBackValidSediment()
            throws IOException {
        var path = directory.resolve("water-fork.chk");
        var before = initial(path);
        var a = water(1,4,4,5,4);
        var b = water(1,4,4,4,5);
        var s = sediment(0,1,4,4,5,4);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,before,
                Map.of(a,5.0,b,3.0),Map.of(s,2.0)));
        assertEquals(before,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void staleWriterCannotEraseEitherReceiptHistory() throws IOException {
        var path = directory.resolve("stale.chk");
        var before = initial(path);
        var first = HydrologyCoupledReceiptBatchV2.receive(path,before,
            Map.of(water(1,4,4,5,4),2.0),
            Map.of(sediment(0,1,4,4,5,4),3.0));
        assertThrows(IOException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,before,
                Map.of(water(2,6,4,5,4),4.0),
                Map.of(sediment(1,2,6,4,5,4),5.0)));
        assertEquals(first.checkpoint(),HydrologyCheckpointStoreV2.load(path));
        assertThrows(IOException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,before,Map.of(),Map.of()));
    }

    @Test void rejectsNewLateReceiptsWithoutPartialPersistence() throws IOException {
        var path = directory.resolve("late.chk");
        var before = initial(path);
        var validWater = water(1,4,4,5,4);
        var validSediment = sediment(0,1,4,4,5,4);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,before,
                Map.of(water(0,6,4,5,4),2.0),Map.of(validSediment,3.0)));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,before,
                Map.of(validWater,2.0),
                Map.of(sediment(0,0,4,4,5,4),3.0)));
        assertEquals(before,HydrologyCheckpointStoreV2.load(path));
    }

    @Test void independentInsertionOrderProducesIdenticalDurableBytes()
            throws IOException {
        var first = new LinkedHashMap<HydrologyTransferLedgerV2.Key,Double>();
        var reverse = new LinkedHashMap<HydrologyTransferLedgerV2.Key,Double>();
        var sFirst = new LinkedHashMap<HydrologySedimentReceiptInboxV2.Receipt,Double>();
        var sReverse = new LinkedHashMap<HydrologySedimentReceiptInboxV2.Receipt,Double>();
        for (int i=0;i<64;i++) {
            first.put(water(i+1,4,4,5,4),i+1.0);
            sFirst.put(sediment(i,i+1,4,4,5,4),i+1.0);
        }
        for (int i=63;i>=0;i--) {
            reverse.put(water(i+1,4,4,5,4),i+1.0);
            sReverse.put(sediment(i,i+1,4,4,5,4),i+1.0);
        }
        var a = directory.resolve("order-a.chk");
        var b = directory.resolve("order-b.chk");
        HydrologyCoupledReceiptBatchV2.receive(a,initial(a),first,sFirst);
        HydrologyCoupledReceiptBatchV2.receive(b,initial(b),reverse,sReverse);
        assertArrayEquals(Files.readAllBytes(a),Files.readAllBytes(b));
    }

    @Test void conflictingHistoricalReplayDoesNotModifyStoredCheckpoint()
            throws IOException {
        var path = directory.resolve("conflict.chk");
        var before = initial(path);
        var w = water(1,4,4,5,4);
        var s = sediment(0,1,4,4,5,4);
        var committed = HydrologyCoupledReceiptBatchV2.receive(
            path,before,Map.of(w,4.0),Map.of(s,3.0)).checkpoint();
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,committed,
                Map.of(w,5.0),Map.of(s,3.0)));
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,committed,
                Map.of(w,4.0),Map.of(s,5.0)));
        assertEquals(committed,HydrologyCheckpointStoreV2.load(path));
    }
    @Test void coupledDurableReceiptsFeedSeasonalErosionWithoutDoubleCounting()
            throws IOException {
        var path = directory.resolve("seasonal-coupled.chk");
        var landscape = new HydrologySeasonalLandscapeStateV2.State(
            "world",1000,
            new HydrologyTerrainOverlayV2.State("world",Map.of()),
            new HydrologySnowSoilStateV2.State("world",Map.of()));
        var initial = HydrologyCheckpointV2.capture(0,
            HydrologySedimentCheckpointV2.initialWorld(
                HydrologySeasonalLandscapeStateV2.encode(landscape)),
            new HydrologyEpochInboxV2());
        HydrologyCheckpointStoreV2.save(path,initial);
        var w = new HydrologyTransferLedgerV2.Key(1,at(3,4),at(4,4));
        var s = sediment(0,1,3,4,4,4);
        var accepted = HydrologyCoupledReceiptBatchV2.receive(
            path,initial,Map.of(w,20.0),Map.of(s,100.0));
        var addresses = new ArrayList<GlobalHydrologyAddressV2.Sample>();
        var weather = new HashMap<GlobalHydrologyAddressV2.Sample,
                                  HydrologySnowSoilStateV2.Forcing>();
        for (int y=4;y<9;y++)
            for (int x=4;x<9;x++) {
                var address = at(x,y);
                addresses.add(address);
                weather.put(address,HydrologySnowSoilStateV2.Forcing
                    .forSphericalCell(address,1000,2,5,0,0,1,10));
            }
        double[] capacity = new double[25];
        Arrays.fill(capacity,2_000_000);
        var candidate = HydrologySeasonalSedimentCandidateV2.stageWithWaterReceipts(
            accepted.checkpoint(),addresses,5,5,
            address -> 20-(address.x()-4)-(address.y()-4),
            weather,1000,2000,.1,.4,capacity,Set.of(24));
        var audit = HydrologySeasonalReceiptAuditV2.verify(
            accepted.checkpoint(),candidate,addresses);
        assertEquals(1,audit.waterReceipts());
        assertEquals(1,audit.sedimentReceipts());
        assertEquals(0,audit.waterCubicMeters().compareTo(
            java.math.BigDecimal.valueOf(20)));
        assertEquals(0,audit.sedimentKilograms().compareTo(
            java.math.BigDecimal.valueOf(100)));
        assertEquals(accepted.checkpoint(),HydrologyCheckpointStoreV2.load(path));
        assertFalse(HydrologyInboxCodecV2.decode(
            accepted.checkpoint().inboxState()).delivered().contains(w));
        assertFalse(HydrologySedimentCheckpointV2.decode(
            accepted.checkpoint().worldState()).sediment().delivered().contains(s));
    }

    @Test void crossResolutionWaterAliasRejectsCoupledSedimentBatch()
            throws IOException {
        var path = directory.resolve("coupled-alias.chk");
        var before = initial(path);
        var fine = water(1,4,4,5,4);
        var coarse = new HydrologyTransferLedgerV2.Key(1,
            new GlobalHydrologyAddressV2.Sample(1,4,2,2),
            new GlobalHydrologyAddressV2.Sample(1,4,3,2));
        var s = sediment(0,1,4,4,5,4);
        assertThrows(IllegalArgumentException.class,() ->
            HydrologyCoupledReceiptBatchV2.receive(path,before,
                Map.of(fine,2.0,coarse,3.0),Map.of(s,4.0)));
        assertEquals(before,HydrologyCheckpointStoreV2.load(path));
    }

}
