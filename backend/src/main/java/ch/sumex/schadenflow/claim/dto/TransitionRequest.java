package ch.sumex.schadenflow.claim.dto;

import ch.sumex.schadenflow.claim.ClaimState;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TransitionRequest(
        @NotNull ClaimState targetState,
        @Size(max = 1000) String reason
) {}
