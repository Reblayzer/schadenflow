package ch.sumex.schadenflow.auth;

import ch.sumex.schadenflow.claim.Role;
import java.util.UUID;

public record AuthenticatedUser(UUID userId, String username, Role role) {
}
