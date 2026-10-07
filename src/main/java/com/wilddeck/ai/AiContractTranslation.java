package com.wilddeck.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AiContractTranslation(
        @JsonProperty("clauses") List<AiContractClauseProposal> clauses
) {}
