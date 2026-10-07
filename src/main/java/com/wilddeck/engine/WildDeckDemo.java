package com.wilddeck.engine;

import java.util.*;

public final class WildDeckDemo {
    public static void main(String[] args) {
        CardDefinition hydraDef = new CardDefinition(
                "hydra","Hydra","Creature",
                Set.of("BIOLOGICAL","MONSTROUS","REGENERATIVE"),
                Set.of("ATTACK","BITE","REGENERATE"),
                8, Map.of(), ""
        );
        CardDefinition eggDef = new CardDefinition(
                "dragon-egg","Dragon Egg","Creature",
                Set.of("BIOLOGICAL","DRACONIC","DORMANT"),
                Set.of("HATCH","GROW"),
                null, Map.of(), ""
        );
        CardDefinition mutagenDef = new CardDefinition(
                "mutagen","Mutagen","Chemical",
                Set.of("CHEMICAL","BIOLOGICAL","UNSTABLE"),
                Set.of("MUTATE","ALTER BIOLOGY"),
                null, Map.of(), ""
        );
        CardDefinition bioDef = new CardDefinition(
                "bio-architect","Bio-Architect","Specialist",
                Set.of("HUMAN","SPECIALIST","BIOLOGICAL SCIENCE"),
                Set.of("ANALYZE BIOLOGY","ENGINEER TRAITS","STABILIZE MUTATION"),
                null, Map.of(), ""
        );

        PlayerState yusuf = new PlayerState("yusuf","Yusuf");
        PlayerState asha = new PlayerState("asha","Asha");

        CardInstance hydra = new CardInstance(hydraDef,"yusuf",Zone.KINGDOM,Visibility.PUBLIC);
        CardInstance egg = new CardInstance(eggDef,"yusuf",Zone.KINGDOM,Visibility.HIDDEN);
        CardInstance mutagen = new CardInstance(mutagenDef,"yusuf",Zone.KINGDOM,Visibility.HIDDEN);
        CardInstance bio = new CardInstance(bioDef,"yusuf",Zone.KINGDOM,Visibility.HIDDEN);
        for (CardInstance card : List.of(hydra,egg,mutagen,bio)) yusuf.deploy(card, card != hydra);

        GameState game = new GameState(GameRules.alphaV1(),List.of(yusuf,asha),List.of(),"yusuf");
        GameEngine engine = new GameEngine();
        engine.initializeResources(game);
        engine.startRound(game,"yusuf");

        Decision decision = new InteractionEngine().validateTraitEngineering(
                game,"yusuf",egg.id(),"REGENERATIVE",
                List.of(hydra.id()),List.of(mutagen.id(),bio.id())
        );

        System.out.println("Trait engineering legal? " + decision.allowed());
        System.out.println("Yusuf resources: " + yusuf.resources().snapshot());
    }
}
