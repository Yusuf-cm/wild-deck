package com.wilddeck.world;

import java.util.*;

/**
 * Explicit, opt-in route authentication for timed water crossing a single
 * boundary edge of a complete, caller-verified spherical drainage atlas.
 *
 * The source and destination must be the exact authoritative downstream edge,
 * not merely nearby cells or members of the same catchment. The returned
 * transfer can be fed into the durable timed-seasonal checkpoint. No water is
 * generated here; the sender must separately debit its own inventory.
 *
 * This verifies routing against ONE immutable atlas, not the sender's debit,
 * the authenticity of a transfer ID, or topology migration across atlases.
 */
public final class HydrologyVerifiedTimedBoundaryV2 {
    private HydrologyVerifiedTimedBoundaryV2() {}

    public record EdgeTransfer(String id,
            GlobalHydrologyAddressV2.Sample source,
            GlobalHydrologyAddressV2.Sample destination,
            long dueTick, double volumeM3) {
        public EdgeTransfer {
            Objects.requireNonNull(source);
            Objects.requireNonNull(destination);
            // Reuse the ledger's ID, time, and positive finite-volume contract.
            new HydrologyTimedTransitLedgerV2.Transfer(id,
                HydrologyGlobalCatchmentsV2.coordinate(destination),
                dueTick, volumeM3);
        }
    }

    /**
     * Reject invented edges, noncanonical shared vertices, terminal sources,
     * unknown unloaded nodes, and conflicting duplicate IDs. The atlas must
     * cover the full verified spherical drainage, including unloaded cells.
     */
    public static List<HydrologyTimedTransitLedgerV2.Transfer> verify(
            HydrologySphericalRouteAtlasV2 atlas,
            Collection<EdgeTransfer> transfers) {
        Objects.requireNonNull(atlas);
        Objects.requireNonNull(transfers);
        var unique = new TreeMap<String,EdgeTransfer>();
        for (var transfer : transfers) {
            Objects.requireNonNull(transfer);
            var source = transfer.source();
            var destination = transfer.destination();
            if (!HydrologySphericalTopologyV2.isCanonical(source) ||
                !HydrologySphericalTopologyV2.isCanonical(destination) ||
                source.equals(destination))
                throw new IllegalArgumentException("Noncanonical or self boundary edge");
            var route = atlas.routeAt(source);
            atlas.routeAt(destination);
            if (route.terminal() || !destination.equals(route.downstream()) ||
                !HydrologySphericalTopologyV2.adjacent(source,destination))
                throw new IllegalArgumentException("Unverified timed boundary edge");
            var sourceCatchment = atlas.catchments().get(source);
            var destinationCatchment = atlas.catchments().get(destination);
            if (sourceCatchment == null || !sourceCatchment.equals(destinationCatchment))
                throw new IllegalArgumentException("Inconsistent global river identity");
            var old = unique.putIfAbsent(transfer.id(),transfer);
            if (old != null && !old.equals(transfer))
                throw new IllegalArgumentException("Conflicting boundary transfer identity");
        }
        var verified = new ArrayList<HydrologyTimedTransitLedgerV2.Transfer>();
        for (var transfer : unique.values())
            verified.add(new HydrologyTimedTransitLedgerV2.Transfer(
                transfer.id(),HydrologyGlobalCatchmentsV2.coordinate(
                    transfer.destination()),transfer.dueTick(),transfer.volumeM3()));
        return List.copyOf(verified);
    }
}
