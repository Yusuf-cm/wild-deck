package com.wilddeck.engine;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CustomContractClauseTest {

    @Test
    void brianSilenceStyleClauseBlocksInformationActionsWithoutConsent() {
        PlayerState yusuf = new PlayerState("yusuf","Yusuf");
        PlayerState brian = new PlayerState("brian","Brian");
        PlayerState michelle = new PlayerState("michelle","Michelle");

        GameState game = new GameState(
                GameRules.alphaV1(),
                List.of(yusuf,brian,michelle),
                List.of(),
                "michelle"
        );

        CustomContractClause silence = new CustomContractClause(
                ClauseEffect.FORBID,
                "Michelle may not investigate, discuss, trade, disclose or spread information about Yusuf and Brian's relationship without both players' consent.",
                Set.of("michelle"),
                Set.of(
                        ContractActionType.INVESTIGATE_INFORMATION,
                        ContractActionType.SPY_INFORMATION,
                        ContractActionType.QUESTION_OTHERS,
                        ContractActionType.DISCLOSE_INFORMATION,
                        ContractActionType.TRADE_INFORMATION,
                        ContractActionType.SPREAD_INFORMATION,
                        ContractActionType.DISCUSS_INFORMATION
                ),
                Set.of(),
                Set.of(),
                Set.of(),
                "RELATIONSHIP:YUSUF:BRIAN",
                Set.of("yusuf","brian")
        );

        Contract contract = new Contract(
                Set.of("yusuf","brian","michelle"),
                Set.of(),
                List.of(silence),
                1,
                4
        );

        ContractEngine contracts = new ContractEngine();
        contracts.registerContract(game,contract);

        ContractActionRequest blocked = new ContractActionRequest(
                "michelle",
                ContractActionType.DISCUSS_INFORMATION,
                null,
                null,
                contract.id(),
                "RELATIONSHIP:YUSUF:BRIAN",
                "outsider",
                Set.of()
        );

        Decision result = contracts.validate(game,blocked);
        assertFalse(result.allowed());

        ContractActionRequest consented = new ContractActionRequest(
                "michelle",
                ContractActionType.DISCUSS_INFORMATION,
                null,
                null,
                contract.id(),
                "RELATIONSHIP:YUSUF:BRIAN",
                "outsider",
                Set.of("yusuf","brian")
        );

        assertTrue(contracts.validate(game,consented).allowed());
    }

    @Test
    void customClauseDoesNotSilenceUnrelatedTopics() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");
        GameState game = new GameState(GameRules.alphaV1(),List.of(a,b),List.of(),"a");

        CustomContractClause clause = new CustomContractClause(
                ClauseEffect.FORBID,
                "Do not discuss the secret tunnel.",
                Set.of("a"),
                Set.of(ContractActionType.DISCUSS_INFORMATION),
                Set.of(),Set.of(),Set.of(),
                "SECRET:TUNNEL",
                Set.of()
        );

        Contract contract = new Contract(Set.of("a","b"),Set.of(),List.of(clause),1,3);
        ContractEngine engine = new ContractEngine();
        engine.registerContract(game,contract);

        ContractActionRequest unrelated = new ContractActionRequest(
                "a",ContractActionType.DISCUSS_INFORMATION,
                null,null,contract.id(),"WEATHER",null,Set.of());

        assertTrue(engine.validate(game,unrelated).allowed());
    }
}
