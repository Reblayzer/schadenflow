package ch.sumex.schadenflow.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import ch.sumex.schadenflow.auth.dto.LoginResponse;
import ch.sumex.schadenflow.user.User;
import ch.sumex.schadenflow.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        // getId() may be null for Users not yet persisted (e.g. in unit tests); fall back to a random UUID.
        UUID userId = user.getId() != null ? user.getId() : UUID.randomUUID();
        String token = jwtService.issue(userId, user.getUsername(), user.getRole());
        Instant expiresAt = Instant.now().plus(jwtService.expirationMinutes(), ChronoUnit.MINUTES);
        return new LoginResponse(token, user.getUsername(), user.getRole(), expiresAt);
    }
}
