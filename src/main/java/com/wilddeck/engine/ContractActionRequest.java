package com.wilddeck.engine;

public record ContractActionRequest(
        String actorPlayerId,
        ContractActionType type,
        String targetPlayerId,
        String relatedPlayerId,
        String contractId
) {}
