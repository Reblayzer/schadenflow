package ch.sumex.schadenflow.claim;

import org.junit.jupiter.api.Test;
import ch.sumex.schadenflow.shared.DomainException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ClaimStateMachineTest {

    private final ClaimStateMachine sm = new ClaimStateMachine();

    @Test
    void legalEdgesAreRecognised() {
        assertThat(sm.isLegalTransition(ClaimState.EINGEREICHT, ClaimState.IN_PRUEFUNG)).isTrue();
        assertThat(sm.isLegalTransition(ClaimState.IN_PRUEFUNG, ClaimState.GENEHMIGT)).isTrue();
        assertThat(sm.isLegalTransition(ClaimState.IN_PRUEFUNG, ClaimState.ABGELEHNT)).isTrue();
        assertThat(sm.isLegalTransition(ClaimState.GENEHMIGT, ClaimState.AUSBEZAHLT)).isTrue();
    }

    @Test
    void illegalEdgesAreRejected() {
        assertThat(sm.isLegalTransition(ClaimState.EINGEREICHT, ClaimState.AUSBEZAHLT)).isFalse();
        assertThat(sm.isLegalTransition(ClaimState.EINGEREICHT, ClaimState.GENEHMIGT)).isFalse();
        assertThat(sm.isLegalTransition(ClaimState.GENEHMIGT, ClaimState.ABGELEHNT)).isFalse();
        assertThat(sm.isLegalTransition(ClaimState.ABGELEHNT, ClaimState.IN_PRUEFUNG)).isFalse();
        assertThat(sm.isLegalTransition(ClaimState.AUSBEZAHLT, ClaimState.GENEHMIGT)).isFalse();
    }

    @Test
    void terminalStatesHaveNoOutgoingEdges() {
        for (ClaimState to : ClaimState.values()) {
            assertThat(sm.isLegalTransition(ClaimState.ABGELEHNT, to)).isFalse();
            assertThat(sm.isLegalTransition(ClaimState.AUSBEZAHLT, to)).isFalse();
        }
    }

    @Test
    void roleRulesAreEnforced() {
        // submit -> in Pruefung: Sachbearbeiter or Admin, not Anspruchsteller
        assertThat(sm.isRoleAllowed(ClaimState.EINGEREICHT, ClaimState.IN_PRUEFUNG, Role.SACHBEARBEITER)).isTrue();
        assertThat(sm.isRoleAllowed(ClaimState.EINGEREICHT, ClaimState.IN_PRUEFUNG, Role.ADMIN)).isTrue();
        assertThat(sm.isRoleAllowed(ClaimState.EINGEREICHT, ClaimState.IN_PRUEFUNG, Role.ANSPRUCHSTELLER)).isFalse();
        // pay out: Admin only
        assertThat(sm.isRoleAllowed(ClaimState.GENEHMIGT, ClaimState.AUSBEZAHLT, Role.ADMIN)).isTrue();
        assertThat(sm.isRoleAllowed(ClaimState.GENEHMIGT, ClaimState.AUSBEZAHLT, Role.SACHBEARBEITER)).isFalse();
    }

    @Test
    void validateThrowsIllegalTransitionForBadEdge() {
        assertThatThrownBy(() -> sm.validateTransition(ClaimState.EINGEREICHT, ClaimState.AUSBEZAHLT, Role.ADMIN))
                .isInstanceOf(DomainException.IllegalTransitionError.class);
    }

    @Test
    void validateThrowsForbiddenForDisallowedRole() {
        assertThatThrownBy(() -> sm.validateTransition(ClaimState.GENEHMIGT, ClaimState.AUSBEZAHLT, Role.SACHBEARBEITER))
                .isInstanceOf(DomainException.ForbiddenError.class);
    }

    @Test
    void validatePassesForAllowedTransition() {
        assertThatCode(() -> sm.validateTransition(ClaimState.IN_PRUEFUNG, ClaimState.GENEHMIGT, Role.SACHBEARBEITER))
                .doesNotThrowAnyException();
    }
}
