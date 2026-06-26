package ch.sumex.schadenflow.triage;

import java.util.ArrayList;
import java.util.List;

import ch.sumex.schadenflow.claim.Category;
import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class AnthropicTriageService implements TriageService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CATEGORY_VALUES =
            "ARZTKOSTEN, MEDIKAMENTE, SPITAL, ZAHNARZT, THERAPIE, HILFSMITTEL, SONSTIGES";
    private static final String FLAG_VALUES =
            "MISSING_AMOUNT, VAGUE_DESCRIPTION, MISSING_DATE, MISSING_PROVIDER";

    private final AnthropicClient client;
    private final String model;

    public AnthropicTriageService(AnthropicClient client, String model) {
        this.client = client;
        this.model = model;
    }

    @Override
    public TriageResult triage(TriageInput input) {
        String prompt = buildPrompt(input);
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(1024L)
                    .addUserMessage(prompt)
                    .build();
            Message response = client.messages().create(params);
            String text = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(t -> t.text())
                    .reduce("", String::concat);
            return parseResponseJson(text);
        } catch (TriageUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new TriageUnavailableException("Triage provider call failed", ex);
        }
    }

    private String buildPrompt(TriageInput input) {
        return """
                Du bist ein Triage-Assistent für Krankenversicherungs-Schadenfälle.
                Analysiere den folgenden Schadenfall und antworte AUSSCHLIESSLICH mit einem JSON-Objekt
                der Form {"summary": string, "suggestedCategory": string, "missingInfoFlags": string[]}.
                suggestedCategory MUSS einer dieser Werte sein: %s.
                missingInfoFlags darf nur diese Werte enthalten: %s.
                Titel: %s
                Beschreibung: %s
                Betrag: %s
                """.formatted(CATEGORY_VALUES, FLAG_VALUES,
                        input.title(), input.description(), String.valueOf(input.amount()));
    }

    TriageResult parseResponseJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String summary = root.path("summary").asText("");
            Category category = parseCategory(root.path("suggestedCategory").asText(""));
            List<MissingInfoFlag> flags = new ArrayList<>();
            for (JsonNode flag : root.path("missingInfoFlags")) {
                parseFlag(flag.asText("")).ifPresent(flags::add);
            }
            return new TriageResult(summary, category, flags);
        } catch (TriageUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TriageUnavailableException("Could not parse triage response", ex);
        }
    }

    private Category parseCategory(String value) {
        try {
            return Category.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return Category.SONSTIGES;
        }
    }

    private java.util.Optional<MissingInfoFlag> parseFlag(String value) {
        try {
            return java.util.Optional.of(MissingInfoFlag.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }
}
