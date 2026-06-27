package ch.sumex.schadenflow.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fails application startup outside the {@code dev} profile when the JWT signing
 * secret is weak or the publicly-known dev default. Inside {@code dev} this bean
 * is absent, so the dev default is tolerated for local runs and tests.
 */
@Configuration
@Profile("!dev")
public class JwtSecretValidationConfig {

    private final String secret;

    public JwtSecretValidationConfig(@Value("${security.jwt.secret}") String secret) {
        this.secret = secret;
    }

    @PostConstruct
    void validateSecret() {
        JwtSecretValidator.validate(secret);
    }
}
