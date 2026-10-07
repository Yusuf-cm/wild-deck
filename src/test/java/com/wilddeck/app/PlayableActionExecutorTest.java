package com.wilddeck.app;

import com.wilddeck.engine.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PlayableActionExecutorTest {

    @Test
    void attackResolvesThroughAuthoritativeWarfareEngine() {
        PlayerState a = new PlayerState("a","A");
        PlayerState b = new PlayerState("b","B");

        CardInstance hydra = deployed(a,card(
                "hydra","Hydra",Set.of("BIOLOGICAL","REGENERATIVE"),
                Set.of("ATTACK","REGENERATE"),8));
        CardInstance guard = deployed(b,card(
                "guard","Guard",Set.of("HUMAN","ARMY"),
                Set.of("ATTACK","DEFEND"),5));

        GameState state = new GameState(
                GameRules.alphaV1(),List.of(a,b),List.of(),"a");

        ActionExecutionResult result = new PlayableActionExecutor()
                .attack(state,"a",hydra.id(),guard.id());

        assertTrue(result.success());
        assertTrue(hydra.damage() > 0);
        assertTrue(guard.damage() > 0 || guard.isDead());
        assertTrue(state.events().stream().anyMatch(e -> e.type().equals("COMBAT")));
    }

    @Test
    void productionAddsOnlyResourcesBackedByProducerCapabilities() {
        PlayerState p = new PlayerState("p","P");
        deployed(p,new CardDefinition(
                "gold","Gold Mine","Structure",
                Set.of("STRUCTURE","RESOURCE"),Set.of("PRODUCE GOLD"),
                null,Map.of(),""));
        deployed(p,new CardDefinition(
                "mana","Mana Shrine","Structure",
                Set.of("STRUCTURE","RESOURCE","MAGIC"),Set.of("PRODUCE MANA"),
                null,Map.of(),""));

        GameState state = new GameState(
                GameRules.alphaV1(),List.of(p),List.of(),"p");

        new ProductionEngine().collectForRound(state);

        assertEquals(1,p.resources().get(ResourceType.GOLD));
        assertEquals(1,p.resources().get(ResourceType.MANA));
        assertEquals(0,p.resources().get(ResourceType.WEALTH));
    }

    private static CardInstance deployed(PlayerState p,CardDefinition def) {
        CardInstance c = new CardInstance(def,p.id(),Zone.KINGDOM,Visibility.PUBLIC);
        p.deploy(c,false);
        return c;
    }

    private static CardDefinition card(
            String id,String name,Set<String> props,Set<String> caps,Integer strength
    ) {
        return new CardDefinition(id,name,"Test",props,caps,strength,Map.of(),"");
    }
}
