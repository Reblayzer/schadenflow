package ch.sumex.schadenflow.triage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import ch.sumex.schadenflow.claim.Category;

public class MockTriageService implements TriageService {

    private static final int VAGUE_DESCRIPTION_MIN_LENGTH = 20;
    private static final Pattern DATE_LIKE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}");

    @Override
    public TriageResult triage(TriageInput input) {
        String description = input.description() == null ? "" : input.description();
        String lower = description.toLowerCase(Locale.ROOT);
        Category category = suggestCategory(lower);
        List<MissingInfoFlag> flags = flags(input, description, lower);
        String summary = summarize(description);
        return new TriageResult(summary, category, flags);
    }

    private Category suggestCategory(String lower) {
        if (lower.contains("zahn")) {
            return Category.ZAHNARZT;
        }
        if (lower.contains("apotheke") || lower.contains("medikament")) {
            return Category.MEDIKAMENTE;
        }
        if (lower.contains("spital") || lower.contains("klinik")) {
            return Category.SPITAL;
        }
        if (lower.contains("therapie")) {
            return Category.THERAPIE;
        }
        if (lower.contains("brille") || lower.contains("hilfsmittel")) {
            return Category.HILFSMITTEL;
        }
        if (lower.contains("arzt")) {
            return Category.ARZTKOSTEN;
        }
        return Category.SONSTIGES;
    }

    private List<MissingInfoFlag> flags(TriageInput input, String description, String lower) {
        List<MissingInfoFlag> flags = new ArrayList<>();
        if (input.amount() == null || input.amount().compareTo(BigDecimal.ZERO) == 0) {
            flags.add(MissingInfoFlag.MISSING_AMOUNT);
        }
        if (description.length() < VAGUE_DESCRIPTION_MIN_LENGTH) {
            flags.add(MissingInfoFlag.VAGUE_DESCRIPTION);
        }
        if (!DATE_LIKE.matcher(description).find()) {
            flags.add(MissingInfoFlag.MISSING_DATE);
        }
        if (!(lower.contains("arzt") || lower.contains("apotheke") || lower.contains("klinik")
                || lower.contains("spital") || lower.contains("optiker") || lower.contains("therapeut"))) {
            flags.add(MissingInfoFlag.MISSING_PROVIDER);
        }
        return List.copyOf(flags);
    }

    private String summarize(String description) {
        String trimmed = description.strip();
        if (trimmed.isEmpty()) {
            return "Keine Beschreibung vorhanden.";
        }
        int firstStop = trimmed.indexOf('.');
        String firstSentence = firstStop > 0 ? trimmed.substring(0, firstStop + 1) : trimmed;
        return firstSentence.length() > 200 ? firstSentence.substring(0, 200) : firstSentence;
    }
}
