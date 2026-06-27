package ch.sumex.schadenflow.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSecretValidationConfigTest {

    // No active profile -> @Profile("!dev") config IS included, mirroring a prod boot.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtSecretValidationConfig.class);

    @Test
    void contextFailsWithTheDevDefaultSecretOutsideDev() {
        runner.withPropertyValues("security.jwt.secret=" + JwtSecretValidator.DEV_DEFAULT_SECRET)
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void contextFailsWithAShortSecretOutsideDev() {
        runner.withPropertyValues("security.jwt.secret=too-short")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void contextStartsWithAStrongSecret() {
        runner.withPropertyValues(
                        "security.jwt.secret=this-is-a-strong-secret-of-sufficient-length-1234")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
