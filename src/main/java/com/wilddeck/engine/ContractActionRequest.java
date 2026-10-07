package com.wilddeck.engine;

import java.util.Set;

public record ContractActionRequest(
        String actorPlayerId,
        ContractActionType type,
        String targetPlayerId,
        String relatedPlayerId,
        String contractId,
        String topicKey,
        String recipientPlayerId,
        Set<String> consentPlayerIds
) {
    public ContractActionRequest(
            String actorPlayerId,
            ContractActionType type,
            String targetPlayerId,
            String relatedPlayerId,
            String contractId
    ) {
        this(actorPlayerId,type,targetPlayerId,relatedPlayerId,contractId,null,null,Set.of());
    }

    public ContractActionRequest {
        consentPlayerIds = consentPlayerIds == null ? Set.of() : Set.copyOf(consentPlayerIds);
    }
}
