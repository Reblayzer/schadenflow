package ch.sumex.schadenflow.claim.dto;

import ch.sumex.schadenflow.claim.ClaimState;
import jakarta.validation.constraints.NotNull;

public record TransitionRequest(
        @NotNull ClaimState targetState,
        String reason
) {}
