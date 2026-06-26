package ch.sumex.schadenflow.triage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TriageConfig {

    @Bean
    @ConditionalOnProperty(name = "schadenflow.triage.provider", havingValue = "mock", matchIfMissing = true)
    public TriageService mockTriageService() {
        return new MockTriageService();
    }

    @Bean
    @ConditionalOnProperty(name = "schadenflow.triage.provider", havingValue = "claude")
    public TriageService anthropicTriageService(
            @org.springframework.beans.factory.annotation.Value("${schadenflow.triage.model}") String model) {
        com.anthropic.client.AnthropicClient client =
                com.anthropic.client.okhttp.AnthropicOkHttpClient.fromEnv();
        return new AnthropicTriageService(client, model);
    }
}
