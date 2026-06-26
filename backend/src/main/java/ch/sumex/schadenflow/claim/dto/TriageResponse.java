package ch.sumex.schadenflow.claim.dto;

import java.util.List;

import ch.sumex.schadenflow.claim.Category;
import ch.sumex.schadenflow.triage.MissingInfoFlag;
import ch.sumex.schadenflow.triage.TriageResult;

public record TriageResponse(String summary, Category suggestedCategory, List<MissingInfoFlag> missingInfoFlags) {

    public static TriageResponse from(TriageResult result) {
        return new TriageResponse(result.summary(), result.suggestedCategory(), result.missingInfoFlags());
    }
}
