package ch.sumex.schadenflow.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import ch.sumex.schadenflow.claim.Role;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-which-is-at-least-32-bytes-long-0123456789";

    private JwtService service(long minutes) {
        return new JwtService(SECRET, minutes);
    }

    @Test
    void issuedTokenHasAlgHS256Header() {
        JwtService jwt = service(60);
        String token = jwt.issue(UUID.randomUUID(), "alice", Role.SACHBEARBEITER);
        String headerJson = new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[0]),
                StandardCharsets.UTF_8);
        assertThat(headerJson).contains("\"alg\":\"HS256\"");
    }

    @Test
    void issueThenParseRoundTrips() {
        UUID userId = UUID.randomUUID();
        JwtService jwt = service(60);
        String token = jwt.issue(userId, "alice", Role.SACHBEARBEITER);

        AuthenticatedUser parsed = jwt.parse(token);
        assertThat(parsed.userId()).isEqualTo(userId);
        assertThat(parsed.username()).isEqualTo("alice");
        assertThat(parsed.role()).isEqualTo(Role.SACHBEARBEITER);
    }

    @Test
    void parseRejectsTamperedToken() {
        JwtService jwt = service(60);
        String token = jwt.issue(UUID.randomUUID(), "alice", Role.ADMIN);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("a") ? "bb" : "aa");
        assertThatThrownBy(() -> jwt.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void parseRejectsTokenSignedWithDifferentSecret() {
        String token = new JwtService("another-secret-which-is-also-32-bytes-long-0123456789", 60)
                .issue(UUID.randomUUID(), "bob", Role.ADMIN);
        assertThatThrownBy(() -> service(60).parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void parseRejectsExpiredToken() {
        JwtService jwt = service(-1); // already expired
        String token = jwt.issue(UUID.randomUUID(), "carol", Role.ANSPRUCHSTELLER);
        assertThatThrownBy(() -> jwt.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void parseRejectsMalformedToken() {
        assertThatThrownBy(() -> service(60).parse("not-a-jwt")).isInstanceOf(JwtException.class);
    }
}
