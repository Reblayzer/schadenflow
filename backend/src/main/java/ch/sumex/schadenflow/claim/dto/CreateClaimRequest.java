package ch.sumex.schadenflow.claim.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateClaimRequest(
        @NotNull UUID claimantId,
        @NotBlank @Size(max = 200) String title,
        @NotBlank String description,
        @NotNull @PositiveOrZero BigDecimal amount
) {}
