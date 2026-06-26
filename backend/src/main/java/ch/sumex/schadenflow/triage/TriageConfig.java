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
}
