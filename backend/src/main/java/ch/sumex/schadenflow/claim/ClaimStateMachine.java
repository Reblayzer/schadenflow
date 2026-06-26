package ch.sumex.schadenflow.claim;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import ch.sumex.schadenflow.shared.DomainException;

@Component
public class ClaimStateMachine {

    /** Allowed transition -> the roles permitted to perform it. */
    private record Edge(ClaimState from, ClaimState to) {}

    private static final Map<Edge, Set<Role>> TRANSITIONS = Map.of(
            new Edge(ClaimState.EINGEREICHT, ClaimState.IN_PRUEFUNG), Set.of(Role.SACHBEARBEITER, Role.ADMIN),
            new Edge(ClaimState.IN_PRUEFUNG, ClaimState.GENEHMIGT), Set.of(Role.SACHBEARBEITER, Role.ADMIN),
            new Edge(ClaimState.IN_PRUEFUNG, ClaimState.ABGELEHNT), Set.of(Role.SACHBEARBEITER, Role.ADMIN),
            new Edge(ClaimState.GENEHMIGT, ClaimState.AUSBEZAHLT), Set.of(Role.ADMIN)
    );

    public boolean isLegalTransition(ClaimState from, ClaimState to) {
        return TRANSITIONS.containsKey(new Edge(from, to));
    }

    public boolean isRoleAllowed(ClaimState from, ClaimState to, Role role) {
        Set<Role> allowed = TRANSITIONS.get(new Edge(from, to));
        return allowed != null && allowed.contains(role);
    }

    public void validateTransition(ClaimState from, ClaimState to, Role role) {
        if (!isLegalTransition(from, to)) {
            throw new DomainException.IllegalTransitionError(
                    "Transition %s -> %s is not allowed".formatted(from, to));
        }
        if (!isRoleAllowed(from, to, role)) {
            throw new DomainException.ForbiddenError(
                    "Role %s may not perform transition %s -> %s".formatted(role, from, to));
        }
    }
}
