package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in recovery of durable sender-journal transfers into one timed seasonal
 * receiver checkpoint. Preflight checks the complete caller-supplied sender
 * fleet and the exact receiver snapshot before preparing the next water epoch.
 *
 * Missing transfers are replayed WITHOUT a second sender debit or rainfall
 * injection. Existing pending/settled IDs remain idempotent in the transit
 * ledger. Only the receiver's local checkpoint is written, using CAS.
 *
 * This is NOT a distributed transaction or a live-world producer lock.
 * Callers must authenticate and quiesce the sender fleet and atlas, and must
 * keep journals until a separate durable acknowledgement protocol exists.
 */
public final class HydrologyTimedBoundaryFleetRecoveryV2 {
    private HydrologyTimedBoundaryFleetRecoveryV2() {}

    public record Recovery(HydrologyTimedBoundaryFleetAuditV2.Audit before,
            HydrologyTimedSeasonalEpochV2.Prepared prepared) {
        public Recovery {
            Objects.requireNonNull(before);
            Objects.requireNonNull(prepared);
        }
    }

    private static HydrologyTimedBoundaryFleetAuditV2.Audit preflight(
            Collection<Path> senderFiles, Path receiverFile,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            boolean strictReceiverCoverage) throws IOException {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(receiverFile);
        if (!HydrologyCheckpointStoreV2.load(receiverFile).equals(expected))
            throw new IOException("Receiver checkpoint changed before fleet recovery");
        // This reads every sender's durable journal, verifies physical owner
        // uniqueness and checks every missing ID against the receiver ledger.
        return HydrologyTimedBoundaryFleetAuditV2.auditCheckpoints(
            senderFiles, receiverFile, atlas, strictReceiverCoverage);
    }

    public static Recovery prepareUniform(
            Collection<Path> senderFiles, Path receiverFile,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            int slices, double durationSeconds,
            boolean strictReceiverCoverage) throws IOException {
        var before = preflight(senderFiles,receiverFile,expected,atlas,
            strictReceiverCoverage);
        var prepared = HydrologyTimedSeasonalEpochV2.prepareStreamingUniformVerified(
            expected,atlas,forcing,lakes,loaded,before.missing(),slices,durationSeconds);
        return new Recovery(before,prepared);
    }

    /** One receiver-local CAS commits both the replayed transit IDs and water epoch. */
    public static Recovery commitUniform(
            Collection<Path> senderFiles, Path receiverFile,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            int slices, double durationSeconds,
            boolean strictReceiverCoverage) throws IOException {
        var recovery = prepareUniform(senderFiles,receiverFile,expected,atlas,
            forcing,lakes,loaded,slices,durationSeconds,strictReceiverCoverage);
        HydrologyCheckpointStoreV2.saveNext(
            receiverFile,expected,recovery.prepared().checkpoint());
        return recovery;
    }

    public static Recovery prepareScheduled(
            Collection<Path> senderFiles, Path receiverFile,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            double durationSeconds, boolean strictReceiverCoverage) throws IOException {
        var before = preflight(senderFiles,receiverFile,expected,atlas,
            strictReceiverCoverage);
        var prepared = HydrologyTimedSeasonalEpochV2.prepareStreamingScheduledVerified(
            expected,atlas,forcing,lakes,schedules,loaded,before.missing(),durationSeconds);
        return new Recovery(before,prepared);
    }

    /** Scheduled lake forcing uses the same exact fleet recovery preflight. */
    public static Recovery commitScheduled(
            Collection<Path> senderFiles, Path receiverFile,
            HydrologyCheckpointV2.Checkpoint expected,
            HydrologySphericalRouteAtlasV2 atlas,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySnowSoilStateV2.Forcing> forcing,
            Map<GlobalHydrologyAddressV2.Sample,HydrologySeasonalLaggedLakeNetworkV2.Lake> lakes,
            Map<GlobalHydrologyAddressV2.Sample,List<HydrologySeasonalLakeIntegratorV2.Interval>> schedules,
            Set<GlobalHydrologyAddressV2.Sample> loaded,
            double durationSeconds, boolean strictReceiverCoverage) throws IOException {
        var recovery = prepareScheduled(senderFiles,receiverFile,expected,atlas,
            forcing,lakes,schedules,loaded,durationSeconds,strictReceiverCoverage);
        HydrologyCheckpointStoreV2.saveNext(
            receiverFile,expected,recovery.prepared().checkpoint());
        return recovery;
    }
}
