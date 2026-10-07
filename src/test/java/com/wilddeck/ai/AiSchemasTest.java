package com.wilddeck.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiSchemasTest {

    @Test
    void actionSchemaUsesRequiredStringsInsteadOfNullableScalarFields() {
        JsonNode schema = AiSchemas.actionProposal();
        JsonNode properties = schema.path("properties");

        assertEquals("string",properties.path("verb").path("type").asText());
        assertEquals("string",properties.path("target_player_id").path("type").asText());
        assertEquals("string",properties.path("card_id").path("type").asText());
        assertFalse(properties.path("verb").path("type").isArray());
    }

    @Test
    void blankWireScalarsConvertBackToNullDomainValues() {
        AiActionProposal proposal = new AiActionProposalWire(
                AiActionKind.DRAW,
                "",
                List.of(),
                List.of(),
                "",
                "",
                false,
                "",
                "Draw for options.",
                0.8
        ).toDomain();

        assertNull(proposal.verb());
        assertNull(proposal.targetPlayerId());
        assertNull(proposal.cardId());
        assertEquals(AiActionKind.DRAW,proposal.kind());
    }
}
