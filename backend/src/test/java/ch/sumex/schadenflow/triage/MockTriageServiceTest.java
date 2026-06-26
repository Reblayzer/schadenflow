package ch.sumex.schadenflow.triage;

import java.math.BigDecimal;

import ch.sumex.schadenflow.claim.Category;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockTriageServiceTest {

    private final MockTriageService service = new MockTriageService();

    @Test
    void suggestsCategoryFromDescriptionKeyword() {
        TriageResult result = service.triage(new TriageInput(
                "Rechnung", "Behandlung beim Zahnarzt, neue Krone.", new BigDecimal("250.00")));
        assertThat(result.suggestedCategory()).isEqualTo(Category.ZAHNARZT);
    }

    @Test
    void defaultsToSonstigesWhenNoKeywordMatches() {
        TriageResult result = service.triage(new TriageInput(
                "Diverses", "Allgemeine unspezifische Auslagen hier beschrieben.", new BigDecimal("40.00")));
        assertThat(result.suggestedCategory()).isEqualTo(Category.SONSTIGES);
    }

    @Test
    void flagsMissingAmountWhenZero() {
        TriageResult result = service.triage(new TriageInput(
                "Apotheke", "Medikament aus der Apotheke gekauft am 2026-01-05.", BigDecimal.ZERO));
        assertThat(result.missingInfoFlags()).contains(MissingInfoFlag.MISSING_AMOUNT);
    }

    @Test
    void flagsVagueDescriptionWhenShort() {
        TriageResult result = service.triage(new TriageInput(
                "X", "kurz", new BigDecimal("10.00")));
        assertThat(result.missingInfoFlags()).contains(MissingInfoFlag.VAGUE_DESCRIPTION);
    }

    @Test
    void flagsMissingDateWhenNoDateLikeToken() {
        TriageResult result = service.triage(new TriageInput(
                "Spital", "Aufenthalt in der Klinik ohne Datumsangabe hier.", new BigDecimal("900.00")));
        assertThat(result.missingInfoFlags()).contains(MissingInfoFlag.MISSING_DATE);
        assertThat(result.suggestedCategory()).isEqualTo(Category.SPITAL);
    }

    @Test
    void summaryIsNonBlankAndDerivedFromDescription() {
        TriageResult result = service.triage(new TriageInput(
                "Brille", "Neue Brille beim Optiker als Hilfsmittel besorgt am 2026-02-01.",
                new BigDecimal("300.00")));
        assertThat(result.summary()).isNotBlank();
        assertThat(result.suggestedCategory()).isEqualTo(Category.HILFSMITTEL);
    }
}
