package com.wilddeck.world;

import java.util.*;

/**
 * In-memory atomic checkpoint boundary for hydrology inbox and opaque world
 * state. A production persistence adapter must durably commit both payloads
 * in one transaction; this class provides no disk or database guarantees.
 */
public final class HydrologyCheckpointV2 {
    private HydrologyCheckpointV2(){}
    public record Checkpoint(long epoch,String worldState,String inboxState) {
        public Checkpoint {
            if(epoch<0)throw new IllegalArgumentException("Invalid checkpoint epoch");
            Objects.requireNonNull(worldState);
            Objects.requireNonNull(inboxState);
            HydrologyInboxCodecV2.decode(inboxState);
        }
    }

    public static Checkpoint capture(long epoch,String serializedWorld,
                                     HydrologyEpochInboxV2 inbox){
        Objects.requireNonNull(inbox);
        return new Checkpoint(epoch,serializedWorld,
            HydrologyInboxCodecV2.encode(inbox.snapshot()));
    }

    public static HydrologyEpochInboxV2 restoreInbox(Checkpoint checkpoint){
        Objects.requireNonNull(checkpoint);
        return HydrologyEpochInboxV2.restore(
            HydrologyInboxCodecV2.decode(checkpoint.inboxState()));
    }
}
