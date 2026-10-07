package com.wilddeck.engine;

import java.util.Objects;
import java.util.UUID;

public final class ContractObligation {
    private final String id = UUID.randomUUID().toString();
    private final String contractId;
    private final ContractTerm term;
    private final String obligatedPlayerId;
    private final String protectedPlayerId;
    private final int createdRound;
    private final int dueRound;
    private ContractObligationStatus status = ContractObligationStatus.OPEN;

    public ContractObligation(
            String contractId,
            ContractTerm term,
            String obligatedPlayerId,
            String protectedPlayerId,
            int createdRound,
            int dueRound
    ) {
        this.contractId = Objects.requireNonNull(contractId);
        this.term = Objects.requireNonNull(term);
        this.obligatedPlayerId = Objects.requireNonNull(obligatedPlayerId);
        this.protectedPlayerId = Objects.requireNonNull(protectedPlayerId);
        this.createdRound = createdRound;
        this.dueRound = dueRound;
    }

    public String id() { return id; }
    public String contractId() { return contractId; }
    public ContractTerm term() { return term; }
    public String obligatedPlayerId() { return obligatedPlayerId; }
    public String protectedPlayerId() { return protectedPlayerId; }
    public int createdRound() { return createdRound; }
    public int dueRound() { return dueRound; }
    public ContractObligationStatus status() { return status; }

    public void fulfill() {
        if (status == ContractObligationStatus.OPEN) status = ContractObligationStatus.FULFILLED;
    }

    public void breach() {
        if (status == ContractObligationStatus.OPEN) status = ContractObligationStatus.BREACHED;
    }
}
