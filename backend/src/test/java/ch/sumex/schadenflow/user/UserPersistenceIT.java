package ch.sumex.schadenflow.user;

import ch.sumex.schadenflow.claim.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("dev")
class UserPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UserRepository userRepository;

    @Test
    void seedUsersExistAndPasswordsVerify() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        var admin = userRepository.findByUsername("admin").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(encoder.matches("password123", admin.getPasswordHash())).isTrue();
        assertThat(encoder.matches("wrong", admin.getPasswordHash())).isFalse();

        var anspruchsteller = userRepository.findByUsername("anspruchsteller").orElseThrow();
        assertThat(anspruchsteller.getRole()).isEqualTo(Role.ANSPRUCHSTELLER);
        assertThat(encoder.matches("password123", anspruchsteller.getPasswordHash())).isTrue();

        var sachbearbeiter = userRepository.findByUsername("sachbearbeiter").orElseThrow();
        assertThat(sachbearbeiter.getRole()).isEqualTo(Role.SACHBEARBEITER);
        assertThat(encoder.matches("password123", sachbearbeiter.getPasswordHash())).isTrue();
    }
}
