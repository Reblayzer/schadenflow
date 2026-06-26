package ch.sumex.schadenflow.triage;

import ch.sumex.schadenflow.claim.Category;
import java.util.List;

public record TriageResult(String summary, Category suggestedCategory, List<MissingInfoFlag> missingInfoFlags) {
}
