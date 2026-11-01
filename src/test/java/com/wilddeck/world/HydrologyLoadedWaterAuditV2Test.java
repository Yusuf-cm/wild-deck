package com.wilddeck.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HydrologyLoadedWaterAuditV2Test {
    @TempDir Path directory;

    private static GlobalHydrologyAddressV2.Sample at(int level,long x,long y) {
        return new GlobalHydrologyAddressV2.Sample(level,2,x,y);
    }
    private static final GlobalHydrologyAddressV2.Sample
        A=at(2,3,2),B=at(2,4,2),C=at(2,3,3),D=at(2,5,2);

    private Path checkpoint(String name,HydrologyEpochInboxV2 inbox,long epoch)
            throws IOException {
        var path=directory.resolve(name+".chk");
        HydrologyCheckpointStoreV2.save(path,HydrologyCheckpointV2.capture(
            epoch,"world",inbox));
        return path;
    }

    private static HydrologyEpochInboxV2 inbox(
            HydrologyTransferLedgerV2.Key key,double amount,boolean deliver) {
        var inbox=new HydrologyEpochInboxV2();
        inbox.receive(key,amount);
        if (deliver) inbox.claimAvailable(key.epoch(),Set.of(key.to()));
        return inbox;
    }

    private static HydrologySedimentLoadedRegionsV2.Receiver receiver(
            Path path,GlobalHydrologyAddressV2.Sample... owned) {
        return new HydrologySedimentLoadedRegionsV2.Receiver(path,List.of(owned));
    }

    @Test void multipleReceiversAccountForAcceptedDeliveredAndPendingMass()
            throws IOException {
        var first=new HydrologyTransferLedgerV2.Key(1,A,B);
        var second=new HydrologyTransferLedgerV2.Key(1,A,C);
        // Use different source epochs to avoid an invalid physical fork.
        second=new HydrologyTransferLedgerV2.Key(2,A,C);
        var west=receiver(checkpoint("west",inbox(first,2.5,true),1),B);
        var east=receiver(checkpoint("east",inbox(second,3.5,false),0),C);
        var regions=Map.of("west",west,"east",east);
        var report=HydrologyLoadedWaterAuditV2.audit(regions);
        assertEquals(new BigDecimal("6.0"),report.accepted());
        assertEquals(new BigDecimal("2.5"),report.delivered());
        assertEquals(new BigDecimal("3.5"),report.pending());
        assertEquals(2,report.acceptedCount());
        assertEquals(1,report.deliveredCount());
        assertFalse(report.settled());
        assertThrows(IllegalStateException.class,()->
            HydrologyLoadedWaterAuditV2.requireSettled(regions));
        assertEquals(report,HydrologyLoadedWaterAuditV2.audit(
            Map.of("east",east,"west",west)));
    }

    @Test void deliveredReceiptsPermitSettledGate() throws IOException {
        var key=new HydrologyTransferLedgerV2.Key(1,A,B);
        var r=receiver(checkpoint("settled",inbox(key,7,true),1),B);
        var result=HydrologyLoadedWaterAuditV2.requireSettled(Map.of("r",r));
        assertTrue(result.settled());
        assertEquals(new BigDecimal("7.0"),result.delivered());
        assertEquals(BigDecimal.ZERO,result.pending());
    }

    @Test void samePhysicalSourceForkedToTwoRegionsIsRejected()
            throws IOException {
        var first=new HydrologyTransferLedgerV2.Key(1,A,B);
        var second=new HydrologyTransferLedgerV2.Key(1,A,C);
        var west=receiver(checkpoint("west",inbox(first,2,false),0),B);
        var east=receiver(checkpoint("east",inbox(second,3,false),0),C);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLoadedWaterAuditV2.audit(Map.of("west",west,"east",east)));
    }

    @Test void mixedResolutionAliasesCannotOwnSamePhysicalWaterVertex()
            throws IOException {
        var a=receiver(checkpoint("a",new HydrologyEpochInboxV2(),0),B);
        // Level 2 x=4,y=2 is the same physical sample as level 1 x=2,y=1.
        var b=receiver(checkpoint("b",new HydrologyEpochInboxV2(),0),
            at(1,2,1));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLoadedWaterAuditV2.audit(Map.of("a",a,"b",b)));
    }

    @Test void unownedArrivalAndAliasedCheckpointFailClosed() throws IOException {
        var key=new HydrologyTransferLedgerV2.Key(1,A,B);
        var path=checkpoint("shared",inbox(key,2,false),0);
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLoadedWaterAuditV2.audit(Map.of("bad",receiver(path,D))));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLoadedWaterAuditV2.audit(Map.of(
                "a",receiver(path,B),"b",receiver(path,C))));
    }

    @Test void consumedEpochBeyondCheckpointIsRejected() throws IOException {
        var snapshot=new HydrologyEpochInboxV2.Snapshot(
            Map.of(),Set.of(2L),Set.of());
        var path=directory.resolve("future.chk");
        HydrologyCheckpointStoreV2.save(path,
            new HydrologyCheckpointV2.Checkpoint(0,"world",
                HydrologyInboxCodecV2.encode(snapshot)));
        assertThrows(IllegalArgumentException.class,()->
            HydrologyLoadedWaterAuditV2.audit(Map.of("r",receiver(path,B))));
    }
}
