package ch.sumex.schadenflow.triage;

import java.math.BigDecimal;

public record TriageInput(String title, String description, BigDecimal amount) {
}
