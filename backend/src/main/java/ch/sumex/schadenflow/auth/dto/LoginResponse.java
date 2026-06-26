package ch.sumex.schadenflow.auth.dto;

import ch.sumex.schadenflow.claim.Role;
import java.time.Instant;

public record LoginResponse(String token, String username, Role role, Instant expiresAt) {
}
