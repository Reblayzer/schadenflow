package ch.sumex.schadenflow.claim.dto;

import ch.sumex.schadenflow.claim.Category;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateClaimRequest(
        @NotNull Category category,
        @Size(max = 2000) String triageSummary) {
}
