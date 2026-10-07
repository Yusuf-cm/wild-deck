package com.wilddeck.engine;

import java.util.*;

public record Contract(
        String id,
        Set<String> participantIds,
        Set<ContractTerm> terms,
        List<CustomContractClause> customClauses,
        int startRound,
        int endRound
) {
    public Contract(Set<String> participants, Set<ContractTerm> terms, int startRound, int endRound) {
        this(UUID.randomUUID().toString(), participants, terms, List.of(), startRound, endRound);
    }

    public Contract(
            Set<String> participants,
            Set<ContractTerm> terms,
            List<CustomContractClause> customClauses,
            int startRound,
            int endRound
    ) {
        this(UUID.randomUUID().toString(), participants, terms, customClauses, startRound, endRound);
    }

    public Contract {
        participantIds = Set.copyOf(participantIds);
        terms = Set.copyOf(terms);
        customClauses = customClauses == null ? List.of() : List.copyOf(customClauses);
        if (participantIds.size() < 2) throw new IllegalArgumentException("contract requires 2+ participants");
        if (terms.isEmpty() && customClauses.isEmpty()) {
            throw new IllegalArgumentException("official contract requires terms or custom clauses");
        }
        if (startRound < 1 || endRound < startRound) throw new IllegalArgumentException("invalid contract rounds");
    }

    public boolean active(int round) { return round >= startRound && round <= endRound; }

    public boolean binds(String a, String b, ContractTerm term, int round) {
        return active(round) && terms.contains(term)
                && participantIds.contains(a) && participantIds.contains(b);
    }
}
