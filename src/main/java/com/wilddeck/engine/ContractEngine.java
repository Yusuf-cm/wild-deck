package com.wilddeck.engine;

import java.util.*;

/**
 * Mechanical enforcement for Official Contracts.
 *
 * Prohibitions are hard-blocked. Positive promises such as MUTUAL_DEFENSE and
 * INTELLIGENCE_SHARING become explicit obligations that are fulfilled or
 * recorded as breaches rather than being silently forgotten.
 */
public final class ContractEngine {

    public void registerContract(GameState state, Contract contract) {
        if (contract.terms().isEmpty() && contract.customClauses().isEmpty()) {
            throw new IllegalArgumentException("official contract needs at least one term or custom clause");
        }
        for (String participantId : contract.participantIds()) {
            state.player(participantId);
        }
        for (CustomContractClause clause : contract.customClauses()) {
            for (String actorId : clause.boundActorIds()) {
                if (!contract.participantIds().contains(actorId)) {
                    throw new IllegalArgumentException("custom clause binds non-participant " + actorId);
                }
            }
            for (String consentId : clause.requiredConsentFrom()) {
                state.player(consentId);
            }
        }
        state.addContract(contract);
    }

    public Decision validate(GameState state, ContractActionRequest request) {
        Objects.requireNonNull(request);
        PlayerState actor = state.player(request.actorPlayerId());

        if (request.targetPlayerId() != null
                && actor.kingdomStatus() == KingdomStatus.VASSAL
                && actor.overlordId().map(request.targetPlayerId()::equals).orElse(false)
                && isHostile(request.type())) {
            return Decision.reject("CONTRACT", "vassalage forbids hostility against overlord");
        }

        for (Contract contract : state.contracts()) {
            if (!contract.active(state.round())) continue;

            if (request.contractId() != null && !request.contractId().equals(contract.id())) {
                continue;
            }

            if (request.targetPlayerId() != null
                    && contract.binds(request.actorPlayerId(), request.targetPlayerId(),
                    ContractTerm.NON_AGGRESSION, state.round())
                    && isHostile(request.type())) {
                return Decision.reject("CONTRACT", "NON_AGGRESSION blocks hostile action");
            }

            if (request.type() == ContractActionType.HOSTILE_PROXY
                    && request.targetPlayerId() != null
                    && contract.binds(request.actorPlayerId(), request.targetPlayerId(),
                    ContractTerm.NO_PROXY_ATTACKS, state.round())) {
                return Decision.reject("CONTRACT", "NO_PROXY_ATTACKS blocks proxy hostility");
            }

            if (request.type() == ContractActionType.HOSTILE_COOPERATION
                    && request.targetPlayerId() != null
                    && contract.binds(request.actorPlayerId(), request.targetPlayerId(),
                    ContractTerm.NO_HOSTILE_COOPERATION, state.round())) {
                return Decision.reject("CONTRACT", "NO_HOSTILE_COOPERATION blocks cooperation");
            }

            if (request.type() == ContractActionType.DISCLOSE_ALLIANCE
                    && contract.terms().contains(ContractTerm.ALLIANCE_SECRECY)
                    && contract.participantIds().contains(request.actorPlayerId())
                    && request.relatedPlayerId() != null
                    && !contract.participantIds().contains(request.relatedPlayerId())) {
                return Decision.reject("CONTRACT", "ALLIANCE_SECRECY blocks disclosure to outsider");
            }

            for (CustomContractClause clause : contract.customClauses()) {
                if (clause.effect() == ClauseEffect.FORBID && clause.matches(request)) {
                    String reason = clause.description().isBlank()
                            ? "custom Official Contract clause blocks action"
                            : clause.description();
                    return Decision.reject("CONTRACT", reason);
                }
            }
        }

        return Decision.allow();
    }

    /**
     * Call when a participant is attacked. Every other participant in an active
     * MUTUAL_DEFENSE contract receives a tracked obligation.
     */
    public List<ContractObligation> onParticipantAttacked(
            GameState state,
            String attackedPlayerId,
            String attackerPlayerId
    ) {
        state.player(attackedPlayerId);
        state.player(attackerPlayerId);
        List<ContractObligation> created = new ArrayList<>();

        for (Contract contract : state.contracts()) {
            if (!contract.active(state.round())
                    || !contract.terms().contains(ContractTerm.MUTUAL_DEFENSE)
                    || !contract.participantIds().contains(attackedPlayerId)
                    || contract.participantIds().contains(attackerPlayerId)) {
                continue;
            }

            for (String participantId : contract.participantIds()) {
                if (participantId.equals(attackedPlayerId)) continue;
                ContractObligation obligation = new ContractObligation(
                        contract.id(),
                        ContractTerm.MUTUAL_DEFENSE,
                        participantId,
                        attackedPlayerId,
                        state.round(),
                        state.round() + 1
                );
                state.addContractObligation(obligation);
                created.add(obligation);
            }
        }
        return List.copyOf(created);
    }

    /**
     * The AI/referee can call this only after it has classified intelligence as
     * confirmed rather than rumor. Java then tracks the contractual duty.
     */
    public List<ContractObligation> onConfirmedThreatKnown(
            GameState state,
            String knowerPlayerId,
            String threatenedPlayerId
    ) {
        state.player(knowerPlayerId);
        state.player(threatenedPlayerId);
        List<ContractObligation> created = new ArrayList<>();

        for (Contract contract : state.contracts()) {
            if (!contract.active(state.round())
                    || !contract.terms().contains(ContractTerm.INTELLIGENCE_SHARING)
                    || !contract.participantIds().contains(knowerPlayerId)
                    || !contract.participantIds().contains(threatenedPlayerId)
                    || knowerPlayerId.equals(threatenedPlayerId)) {
                continue;
            }

            ContractObligation obligation = new ContractObligation(
                    contract.id(),
                    ContractTerm.INTELLIGENCE_SHARING,
                    knowerPlayerId,
                    threatenedPlayerId,
                    state.round(),
                    state.round() + 1
            );
            state.addContractObligation(obligation);
            created.add(obligation);
        }
        return List.copyOf(created);
    }

    public void fulfill(GameState state, String obligationId, String playerId) {
        ContractObligation obligation = state.contractObligation(obligationId)
                .orElseThrow(() -> new IllegalArgumentException("obligation not found: " + obligationId));
        if (!obligation.obligatedPlayerId().equals(playerId)) {
            throw new IllegalStateException("player does not own this obligation");
        }
        obligation.fulfill();
    }

    public List<ContractObligation> auditOverdue(GameState state) {
        List<ContractObligation> breached = new ArrayList<>();
        for (ContractObligation obligation : state.contractObligations()) {
            if (obligation.status() == ContractObligationStatus.OPEN
                    && state.round() > obligation.dueRound()) {
                obligation.breach();
                breached.add(obligation);
            }
        }
        return List.copyOf(breached);
    }

    private boolean isHostile(ContractActionType type) {
        return type == ContractActionType.HOSTILE_DIRECT
                || type == ContractActionType.HOSTILE_PROXY
                || type == ContractActionType.HOSTILE_COOPERATION;
    }
}
