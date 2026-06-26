package ch.sumex.schadenflow.triage;

import java.math.BigDecimal;
import java.util.List;

import ch.sumex.schadenflow.claim.Category;
import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.services.blocking.MessageService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.anthropic.models.messages.MessageCreateParams;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnthropicTriageServiceTest {

    private final AnthropicClient client = mock(AnthropicClient.class);
    private final MessageService messages = mock(MessageService.class);

    private AnthropicTriageService service() {
        when(client.messages()).thenReturn(messages);
        return new AnthropicTriageService(client, "claude-opus-4-8");
    }

    @Test
    void extractsAndConcatenatesTextFromResponseContent() {
        // Mirrors the SDK-call path: response.content() -> text blocks -> concatenated JSON.
        List<ContentBlock> content = List.of(
                ContentBlock.ofText(textBlock("{\"summary\":\"Zahn\",")),
                ContentBlock.ofText(textBlock("\"suggestedCategory\":\"ZAHNARZT\",\"missingInfoFlags\":[\"MISSING_DATE\"]}")));

        String text = AnthropicTriageService.extractText(content);

        TriageResult result = new AnthropicTriageService(client, "claude-opus-4-8").parseResponseJson(text);
        assertThat(result.summary()).isEqualTo("Zahn");
        assertThat(result.suggestedCategory()).isEqualTo(Category.ZAHNARZT);
        assertThat(result.missingInfoFlags()).containsExactly(MissingInfoFlag.MISSING_DATE);
    }

    private static TextBlock textBlock(String text) {
        return TextBlock.builder().text(text).citations(List.of()).build();
    }

    @Test
    void mapsApiFailureToTriageUnavailable() {
        when(client.messages()).thenReturn(messages);
        when(messages.create(any(MessageCreateParams.class))).thenThrow(new RuntimeException("boom"));
        AnthropicTriageService service = new AnthropicTriageService(client, "claude-opus-4-8");

        assertThatThrownBy(() -> service.triage(new TriageInput("t", "Behandlung beim Arzt.", new BigDecimal("10.00"))))
                .isInstanceOf(TriageUnavailableException.class);
    }

    @Test
    void parsesUnknownCategoryToSonstiges() {
        AnthropicTriageService service = service();
        // parseResponseJson is package-private and pure — verify the JSON->domain mapping directly.
        TriageResult result = service.parseResponseJson(
                "{\"summary\":\"Kurz\",\"suggestedCategory\":\"NICHT_BEKANNT\",\"missingInfoFlags\":[\"MISSING_DATE\"]}");
        assertThat(result.suggestedCategory()).isEqualTo(Category.SONSTIGES);
        assertThat(result.missingInfoFlags()).contains(MissingInfoFlag.MISSING_DATE);
        assertThat(result.summary()).isEqualTo("Kurz");
    }

    @Test
    void parsesKnownCategoryAndIgnoresUnknownFlag() {
        AnthropicTriageService service = service();
        TriageResult result = service.parseResponseJson(
                "{\"summary\":\"S\",\"suggestedCategory\":\"ZAHNARZT\",\"missingInfoFlags\":[\"BOGUS\",\"MISSING_AMOUNT\"]}");
        assertThat(result.suggestedCategory()).isEqualTo(Category.ZAHNARZT);
        assertThat(result.missingInfoFlags()).containsExactly(MissingInfoFlag.MISSING_AMOUNT);
    }
}
