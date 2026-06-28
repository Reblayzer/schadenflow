package ch.sumex.schadenflow.auth;

import java.nio.charset.StandardCharsets;

/**
 * Validates the configured JWT signing secret. Used by a {@code @Profile("!dev")}
 * startup check so a non-dev boot aborts with a clear message rather than running
 * with the publicly-known dev default. (JJWT's {@code hmacShaKeyFor} also rejects
 * short keys, but later and with a cryptic message.)
 */
public final class JwtSecretValidator {

    public static final String DEV_DEFAULT_SECRET = "dev-only-insecure-secret-change-me-0123456789";

    private static final int MIN_BYTES = 32;

    private JwtSecretValidator() {
    }

    public static void validate(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "security.jwt.secret must be set to a strong value outside the 'dev' profile");
        }
        if (secret.equals(DEV_DEFAULT_SECRET)) {
            throw new IllegalStateException(
                    "security.jwt.secret is the insecure dev default; set a strong SECURITY_JWT_SECRET "
                            + "outside the 'dev' profile");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_BYTES) {
            throw new IllegalStateException(
                    "security.jwt.secret must be at least 32 bytes; set a strong SECURITY_JWT_SECRET "
                            + "outside the 'dev' profile");
        }
    }
}
