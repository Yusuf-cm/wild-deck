package com.wilddeck.engine;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ContractEngineTest {

    @Test
    void nonAggressionHardBlocksDirectHostility() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");
        GameState game = game(a,b);

        Contract contract = new Contract(
                Set.of("a","b"),
                Set.of(ContractTerm.NON_AGGRESSION),
                1,4
        );
        ContractEngine contracts = new ContractEngine();
        contracts.registerContract(game,contract);

        Decision decision = contracts.validate(
                game,
                new ContractActionRequest(
                        "a",ContractActionType.HOSTILE_DIRECT,"b",null,null));

        assertFalse(decision.allowed());
    }

    @Test
    void noProxyAttackAndHostileCooperationAreEnforced() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");
        GameState game = game(a,b);

        Contract contract = new Contract(
                Set.of("a","b"),
                Set.of(ContractTerm.NO_PROXY_ATTACKS,ContractTerm.NO_HOSTILE_COOPERATION),
                1,5
        );
        ContractEngine contracts = new ContractEngine();
        contracts.registerContract(game,contract);

        assertFalse(contracts.validate(game,new ContractActionRequest(
                "a",ContractActionType.HOSTILE_PROXY,"b","third",null)).allowed());

        assertFalse(contracts.validate(game,new ContractActionRequest(
                "a",ContractActionType.HOSTILE_COOPERATION,"b","third",null)).allowed());
    }

    @Test
    void allianceSecrecyBlocksDisclosureToOutsider() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");
        PlayerState outsider = new PlayerState("x","X");
        GameState game = game(a,b,outsider);

        Contract secret = new Contract(
                Set.of("a","b"),
                Set.of(ContractTerm.ALLIANCE_SECRECY),
                1,6
        );
        ContractEngine contracts = new ContractEngine();
        contracts.registerContract(game,secret);

        Decision toOutsider = contracts.validate(game,new ContractActionRequest(
                "a",ContractActionType.DISCLOSE_ALLIANCE,null,"x",secret.id()));

        Decision toPartner = contracts.validate(game,new ContractActionRequest(
                "a",ContractActionType.DISCLOSE_ALLIANCE,null,"b",secret.id()));

        assertFalse(toOutsider.allowed());
        assertTrue(toPartner.allowed());
    }

    @Test
    void attackCreatesMutualDefenseObligation() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");
        PlayerState ally = new PlayerState("ally","Ally");
        GameState game = game(a,b,ally);

        Contract mutualDefense = new Contract(
                Set.of("b","ally"),
                Set.of(ContractTerm.MUTUAL_DEFENSE),
                1,5
        );
        ContractEngine contracts = new ContractEngine();
        contracts.registerContract(game,mutualDefense);

        List<ContractObligation> obligations =
                contracts.onParticipantAttacked(game,"b","a");

        assertEquals(1,obligations.size());
        assertEquals("ally",obligations.get(0).obligatedPlayerId());
        assertEquals("b",obligations.get(0).protectedPlayerId());
        assertEquals(ContractObligationStatus.OPEN,obligations.get(0).status());
    }

    @Test
    void mutualDefenseObligationCanBeFulfilled() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");
        GameState game = game(a,b);

        Contract contract = new Contract(
                Set.of("a","b"),Set.of(ContractTerm.MUTUAL_DEFENSE),1,5);
        ContractEngine contracts = new ContractEngine();
        contracts.registerContract(game,contract);

        ContractObligation obligation = new ContractObligation(
                contract.id(),ContractTerm.MUTUAL_DEFENSE,
                "a","b",1,2
        );
        game.addContractObligation(obligation);
        contracts.fulfill(game,obligation.id(),"a");

        assertEquals(ContractObligationStatus.FULFILLED,obligation.status());
    }

    @Test
    void overduePositiveObligationBecomesRecordedBreach() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");
        GameState game = game(a,b);

        Contract contract = new Contract(
                Set.of("a","b"),Set.of(ContractTerm.INTELLIGENCE_SHARING),1,8);
        ContractEngine contracts = new ContractEngine();
        contracts.registerContract(game,contract);

        ContractObligation obligation = new ContractObligation(
                contract.id(),ContractTerm.INTELLIGENCE_SHARING,
                "a","b",1,1
        );
        game.addContractObligation(obligation);
        game.nextRound();

        List<ContractObligation> breaches = contracts.auditOverdue(game);

        assertEquals(1,breaches.size());
        assertEquals(ContractObligationStatus.BREACHED,obligation.status());
    }

    @Test
    void confirmedThreatCreatesIntelligenceSharingDuty() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");
        GameState game = game(a,b);

        Contract contract = new Contract(
                Set.of("a","b"),Set.of(ContractTerm.INTELLIGENCE_SHARING),1,6);
        ContractEngine contracts = new ContractEngine();
        contracts.registerContract(game,contract);

        List<ContractObligation> obligations =
                contracts.onConfirmedThreatKnown(game,"a","b");

        assertEquals(1,obligations.size());
        assertEquals(ContractTerm.INTELLIGENCE_SHARING,obligations.get(0).term());
        assertEquals("a",obligations.get(0).obligatedPlayerId());
    }

    private static GameState game(PlayerState... players) {
        return new GameState(GameRules.alphaV1(),List.of(players),List.of(),players[0].id());
    }
}
