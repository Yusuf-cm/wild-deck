package com.wilddeck.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Single-writer checkpointed water epoch. Stage inbox consumption on a copy,
 * solve the loaded flow graph, then commit world+inbox in one local checkpoint.
 * If solve or checkpoint write fails, the caller's inbox remains unchanged.
 *
 * The returned inbox must replace the old one after successful save. The
 * caller is responsible for loading the last committed world and inbox on
 * restart, and for supplying the correct epoch and serialized world state.
 */
public final class HydrologyDurableEpochV2 {
    private HydrologyDurableEpochV2(){}

    public record Committed(HydrologyFlowGraphV2.Result flow,
                            HydrologyEpochInboxV2 inbox,
                            HydrologyCheckpointV2.Checkpoint checkpoint) {
        public Committed {
            Objects.requireNonNull(flow);
            Objects.requireNonNull(inbox);
            Objects.requireNonNull(checkpoint);
        }
    }

    public static Committed commit(Path destination,long epoch,String serializedWorld,
            HydrologyEpochInboxV2 inbox,
            Collection<? extends Collection<HydrologyFlowGraphV2.Node>> mosaics)
            throws IOException {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(serializedWorld);
        Objects.requireNonNull(inbox);
        Objects.requireNonNull(mosaics);
        // Never mutate the original inbox before durable checkpoint success.
        var staged=HydrologyEpochInboxV2.restore(inbox.snapshot());
        var incoming=staged.claim(epoch);
        var result=HydrologyEpochSolverV2.solve(mosaics,incoming);
        var checkpoint=HydrologyCheckpointV2.capture(epoch,serializedWorld,staged);
        HydrologyCheckpointStoreV2.save(destination,checkpoint);
        return new Committed(result,staged,checkpoint);
    }
}
