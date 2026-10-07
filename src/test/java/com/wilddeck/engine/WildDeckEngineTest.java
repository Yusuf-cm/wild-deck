package com.wilddeck.engine;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WildDeckEngineTest {

    @Test
    void hiddenDeploymentLeavesHand() {
        CardDefinition hydra = card("hydra","Hydra",Set.of("BIOLOGICAL"),Set.of("ATTACK"),8);
        PlayerState p1 = new PlayerState("p1","One");
        PlayerState p2 = new PlayerState("p2","Two");
        CardInstance instance = new CardInstance(hydra,"p1",Zone.HAND,Visibility.HIDDEN);
        p1.addToHand(instance);

        GameState game = new GameState(GameRules.alphaV1(),List.of(p1,p2),List.of(),"p1");
        new GameEngine().playFromHand(game,"p1",instance.id(),true);

        assertTrue(p1.hand().isEmpty());
        assertEquals(Zone.KINGDOM,instance.zone());
        assertEquals(Visibility.HIDDEN,instance.visibility());
        assertFalse(p2.knows(instance.id()));
    }

    @Test
    void drawAndPlayShareOneMainAction() {
        CardDefinition hydra = card("hydra","Hydra",Set.of("BIOLOGICAL"),Set.of("ATTACK"),8);
        CardDefinition roc = card("roc","Roc",Set.of("BIOLOGICAL"),Set.of("ATTACK"),7);

        PlayerState p = new PlayerState("p","Player");
        CardInstance hydraInHand = new CardInstance(hydra,"p",Zone.HAND,Visibility.HIDDEN);
        p.addToHand(hydraInHand);

        GameState game = new GameState(GameRules.alphaV1(),List.of(p),
                List.of(new CardInstance(roc,"deck",Zone.DECK,Visibility.HIDDEN)),"p");

        GameEngine engine = new GameEngine();
        engine.draw(game,"p");

        assertThrows(IllegalStateException.class,
                () -> engine.playFromHand(game,"p",hydraInHand.id(),false));
    }

    @Test
    void conservationRuleAllowsRegenerationFromHydra() {
        CardDefinition hydraDef = card("hydra","Hydra",
                Set.of("BIOLOGICAL","REGENERATIVE"),Set.of("ATTACK","REGENERATE"),8);
        CardDefinition eggDef = card("egg","Dragon Egg",
                Set.of("BIOLOGICAL","DRACONIC"),Set.of("HATCH"),null);
        CardDefinition mutagenDef = card("mutagen","Mutagen",
                Set.of("BIOLOGICAL"),Set.of("MUTATE","ALTER BIOLOGY"),null);
        CardDefinition bioDef = card("bio","Bio-Architect",
                Set.of("HUMAN"),Set.of("ENGINEER TRAITS","STABILIZE MUTATION"),null);

        PlayerState p = new PlayerState("p","Player");
        CardInstance hydra = deploy(hydraDef,"p");
        CardInstance egg = deploy(eggDef,"p");
        CardInstance mutagen = deploy(mutagenDef,"p");
        CardInstance bio = deploy(bioDef,"p");
        for (CardInstance c : List.of(hydra,egg,mutagen,bio)) p.deploy(c,true);

        GameState game = new GameState(GameRules.alphaV1(),List.of(p),List.of(),"p");
        Decision decision = new InteractionEngine().validateTraitEngineering(
                game,"p",egg.id(),"REGENERATIVE",
                List.of(hydra.id()),List.of(mutagen.id(),bio.id()));

        assertTrue(decision.allowed());
    }

    @Test
    void conservationRuleRejectsInventedTrait() {
        CardDefinition eggDef = card("egg","Dragon Egg",
                Set.of("BIOLOGICAL","DRACONIC"),Set.of("HATCH"),null);
        CardDefinition mutagenDef = card("mutagen","Mutagen",
                Set.of("BIOLOGICAL"),Set.of("MUTATE","ALTER BIOLOGY"),null);
        CardDefinition bioDef = card("bio","Bio-Architect",
                Set.of("HUMAN"),Set.of("ENGINEER TRAITS","STABILIZE MUTATION"),null);

        PlayerState p = new PlayerState("p","Player");
        CardInstance egg = deploy(eggDef,"p");
        CardInstance mutagen = deploy(mutagenDef,"p");
        CardInstance bio = deploy(bioDef,"p");
        for (CardInstance c : List.of(egg,mutagen,bio)) p.deploy(c,true);

        GameState game = new GameState(GameRules.alphaV1(),List.of(p),List.of(),"p");
        Decision decision = new InteractionEngine().validateTraitEngineering(
                game,"p",egg.id(),"REGENERATIVE",
                List.of(egg.id()),List.of(mutagen.id(),bio.id()));

        assertFalse(decision.allowed());
    }

    @Test
    void nonAggressionBlocksAttack() {
        CardDefinition hydraDef = card("hydra","Hydra",Set.of("BIOLOGICAL"),Set.of("ATTACK"),8);
        PlayerState p1 = new PlayerState("p1","One");
        PlayerState p2 = new PlayerState("p2","Two");
        CardInstance hydra = deploy(hydraDef,"p1");
        p1.deploy(hydra,false);

        GameState game = new GameState(GameRules.alphaV1(),List.of(p1,p2),List.of(),"p1");
        game.addContract(new Contract(Set.of("p1","p2"),Set.of(ContractTerm.NON_AGGRESSION),1,4));

        Decision decision = new InteractionEngine().validate(
                game,new ActionIntent("p1","ATTACK",List.of(hydra.id()),List.of(),"p2"));

        assertFalse(decision.allowed());
    }

    @Test
    void alphaPassiveIncomeIsWealthOnly() {
        PlayerState p = new PlayerState("p","Player");
        GameState game = new GameState(GameRules.alphaV1(),List.of(p),List.of(),"p");
        GameEngine engine = new GameEngine();
        engine.initializeResources(game);
        engine.startRound(game,"p");

        assertEquals(1,p.resources().get(ResourceType.WEALTH));
        assertEquals(0,p.resources().get(ResourceType.GOLD));
        assertEquals(0,p.resources().get(ResourceType.MANA));
    }

    private static CardDefinition card(String id,String name,Set<String> props,Set<String> caps,Integer strength) {
        return new CardDefinition(id,name,"Test",props,caps,strength,Map.of(),"");
    }

    private static CardInstance deploy(CardDefinition def,String owner) {
        return new CardInstance(def,owner,Zone.KINGDOM,Visibility.HIDDEN);
    }
}
