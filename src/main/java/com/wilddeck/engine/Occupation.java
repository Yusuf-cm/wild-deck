package com.wilddeck.engine;

import java.util.Objects;
import java.util.UUID;

public final class Occupation {
    private final String id = UUID.randomUUID().toString();
    private final String occupierPlayerId;
    private final String defenderPlayerId;
    private final String occupierCardId;
    private final String targetCardId;
    private final int startedRound;
    private OccupationStatus status = OccupationStatus.ACTIVE;

    public Occupation(
            String occupierPlayerId,
            String defenderPlayerId,
            String occupierCardId,
            String targetCardId,
            int startedRound
    ) {
        this.occupierPlayerId = Objects.requireNonNull(occupierPlayerId);
        this.defenderPlayerId = Objects.requireNonNull(defenderPlayerId);
        this.occupierCardId = Objects.requireNonNull(occupierCardId);
        this.targetCardId = Objects.requireNonNull(targetCardId);
        if (startedRound < 1) throw new IllegalArgumentException("startedRound must be >= 1");
        this.startedRound = startedRound;
    }

    public String id() { return id; }
    public String occupierPlayerId() { return occupierPlayerId; }
    public String defenderPlayerId() { return defenderPlayerId; }
    public String occupierCardId() { return occupierCardId; }
    public String targetCardId() { return targetCardId; }
    public int startedRound() { return startedRound; }
    public OccupationStatus status() { return status; }

    public boolean survivedTo(int currentRound) {
        return currentRound > startedRound;
    }

    public void breakOccupation() {
        if (status == OccupationStatus.ACTIVE) status = OccupationStatus.BROKEN;
    }

    public void complete() {
        if (status != OccupationStatus.ACTIVE)
            throw new IllegalStateException("occupation is not active");
        status = OccupationStatus.COMPLETED;
    }
}
