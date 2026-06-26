package ch.sumex.schadenflow.auth;

import ch.sumex.schadenflow.auth.dto.LoginResponse;
import ch.sumex.schadenflow.claim.Role;
import ch.sumex.schadenflow.user.User;
import ch.sumex.schadenflow.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final JwtService jwtService = new JwtService(
            "test-secret-which-is-at-least-32-bytes-long-0123456789", 60);
    private final AuthService authService = new AuthService(userRepository, encoder, jwtService);

    private User userWithPassword(String username, Role role, String rawPassword) {
        User user = new User(username, encoder.encode(rawPassword), role);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void loginWithValidCredentialsReturnsToken() {
        User admin = userWithPassword("admin", Role.ADMIN, "password123");
        UUID adminId = admin.getId();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        LoginResponse response = authService.login("admin", "password123");

        assertThat(response.token()).isNotBlank();
        assertThat(response.username()).isEqualTo("admin");
        assertThat(response.role()).isEqualTo(Role.ADMIN);
        assertThat(jwtService.parse(response.token()).role()).isEqualTo(Role.ADMIN);
        assertThat(jwtService.parse(response.token()).userId()).isEqualTo(adminId);
    }

    @Test
    void loginWithWrongPasswordThrowsInvalidCredentials() {
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.of(userWithPassword("admin", Role.ADMIN, "password123")));
        assertThatThrownBy(() -> authService.login("admin", "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginWithUnknownUserThrowsInvalidCredentials() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.login("ghost", "password123"))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
