package ch.sumex.schadenflow.claim.dto;

import ch.sumex.schadenflow.claim.Category;
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
        Category category,
        BigDecimal amount,
        ClaimState state,
        String triageSummary,
        Instant createdAt,
        Instant updatedAt
) {
    public static ClaimResponse from(Claim c) {
        return new ClaimResponse(c.getId(), c.getClaimantId(), c.getTitle(), c.getDescription(),
                c.getCategory(), c.getAmount(), c.getState(), c.getTriageSummary(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
