package ch.sumex.schadenflow.claim.dto;

import ch.sumex.schadenflow.claim.Claim;
import ch.sumex.schadenflow.claim.ClaimState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ClaimResponse(
        UUID id,
        UUID claimantId,
        String title,
        String description,
        String category,
        BigDecimal amount,
        ClaimState state,
        Instant createdAt,
        Instant updatedAt
) {
    public static ClaimResponse from(Claim c) {
        return new ClaimResponse(c.getId(), c.getClaimantId(), c.getTitle(), c.getDescription(),
                c.getCategory(), c.getAmount(), c.getState(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
