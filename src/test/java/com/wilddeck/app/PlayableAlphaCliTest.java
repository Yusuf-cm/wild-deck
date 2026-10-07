package com.wilddeck.app;

import com.wilddeck.engine.CardInstance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PlayableAlphaCliTest {

    @Test
    void resolvesExactAndUniquePrefixCardNamesFromHand() {
        PlayableSession session = PlayableSession.standard(42,null);
        List<CardInstance> hand = session.state().player("player").hand();

        Optional<CardInstance> exact =
                PlayableAlphaCli.findHandCardByName(hand,"Mana Shrine");
        Optional<CardInstance> prefix =
                PlayableAlphaCli.findHandCardByName(hand,"Mana");

        assertTrue(exact.isPresent());
        assertEquals("Mana Shrine",exact.orElseThrow().definition().name());
        assertTrue(prefix.isPresent());
        assertEquals("Mana Shrine",prefix.orElseThrow().definition().name());
    }

    @Test
    void doesNotGuessWhenCardNameDoesNotMatch() {
        PlayableSession session = PlayableSession.standard(42,null);
        List<CardInstance> hand = session.state().player("player").hand();

        assertTrue(
                PlayableAlphaCli.findHandCardByName(hand,"Definitely Not A Card")
                        .isEmpty()
        );
    }
}
