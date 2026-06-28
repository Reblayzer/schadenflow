package ch.sumex.schadenflow.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSecretValidatorTest {

    @Test
    void rejectsTheDevDefaultSecret() {
        assertThatThrownBy(() -> JwtSecretValidator.validate(JwtSecretValidator.DEV_DEFAULT_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev default");
    }

    @Test
    void rejectsASecretShorterThan32Bytes() {
        assertThatThrownBy(() -> JwtSecretValidator.validate("too-short-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void rejectsABlankSecret() {
        assertThatThrownBy(() -> JwtSecretValidator.validate("   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> JwtSecretValidator.validate(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsAStrongNonDefaultSecret() {
        assertThatCode(() -> JwtSecretValidator.validate("this-is-a-strong-secret-of-sufficient-length-1234"))
                .doesNotThrowAnyException();
    }
}
