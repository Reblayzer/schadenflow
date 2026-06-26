package ch.sumex.schadenflow.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ch.sumex.schadenflow.audit.AuditEntry;
import ch.sumex.schadenflow.audit.AuditRepository;
import ch.sumex.schadenflow.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    @Mock ClaimRepository claimRepository;
    @Mock AuditRepository auditRepository;
    @Spy ClaimStateMachine stateMachine = new ClaimStateMachine();
    @InjectMocks ClaimService service;

    private Claim sampleClaim(ClaimState state) {
        return new Claim(UUID.randomUUID(), UUID.randomUUID(), "t", "d", null,
                new BigDecimal("10.00"), state, java.time.Instant.now(), java.time.Instant.now());
    }

    @Test
    void createPersistsClaimAndCreationAuditRow() {
        UUID claimant = UUID.randomUUID();
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        Claim result = service.create(claimant, "Broken arm", "Fell", new BigDecimal("250.00"),
                claimant, Role.ANSPRUCHSTELLER);

        assertThat(result.getState()).isEqualTo(ClaimState.EINGEREICHT);
        ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditRepository).save(audit.capture());
        assertThat(audit.getValue().getFromState()).isNull();
        assertThat(audit.getValue().getToState()).isEqualTo(ClaimState.EINGEREICHT);
        assertThat(audit.getValue().getActorId()).isEqualTo(claimant);
    }

    @Test
    void transitionUpdatesStateAndAppendsAudit() {
        Claim claim = sampleClaim(ClaimState.IN_PRUEFUNG);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID actor = UUID.randomUUID();
        Claim result = service.transition(claim.getId(), ClaimState.GENEHMIGT, "looks valid",
                actor, Role.SACHBEARBEITER);

        assertThat(result.getState()).isEqualTo(ClaimState.GENEHMIGT);
        ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditRepository).save(audit.capture());
        assertThat(audit.getValue().getFromState()).isEqualTo(ClaimState.IN_PRUEFUNG);
        assertThat(audit.getValue().getToState()).isEqualTo(ClaimState.GENEHMIGT);
        assertThat(audit.getValue().getActorRole()).isEqualTo(Role.SACHBEARBEITER);
    }

    @Test
    void transitionOnMissingClaimThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(claimRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transition(id, ClaimState.IN_PRUEFUNG, null,
                UUID.randomUUID(), Role.SACHBEARBEITER))
                .isInstanceOf(DomainException.NotFoundError.class);
    }

    @Test
    void illegalTransitionThrowsAndWritesNoAudit() {
        Claim claim = sampleClaim(ClaimState.EINGEREICHT);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        assertThatThrownBy(() -> service.transition(claim.getId(), ClaimState.AUSBEZAHLT, null,
                UUID.randomUUID(), Role.ADMIN))
                .isInstanceOf(DomainException.IllegalTransitionError.class);
        verify(auditRepository, never()).save(any());
    }

    @Test
    void disallowedRoleThrowsForbidden() {
        Claim claim = sampleClaim(ClaimState.GENEHMIGT);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        assertThatThrownBy(() -> service.transition(claim.getId(), ClaimState.AUSBEZAHLT, null,
                UUID.randomUUID(), Role.SACHBEARBEITER))
                .isInstanceOf(DomainException.ForbiddenError.class);
    }

    @Test
    void rejectWithoutReasonThrowsValidation() {
        Claim claim = sampleClaim(ClaimState.IN_PRUEFUNG);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        assertThatThrownBy(() -> service.transition(claim.getId(), ClaimState.ABGELEHNT, "  ",
                UUID.randomUUID(), Role.SACHBEARBEITER))
                .isInstanceOf(DomainException.ValidationError.class);
        verify(auditRepository, never()).save(any());
    }

    @Test
    void getByIdMissingThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(claimRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(DomainException.NotFoundError.class);
    }

    @Test
    void getAuditThrowsNotFoundWhenClaimAbsent() {
        UUID id = UUID.randomUUID();
        when(claimRepository.existsById(id)).thenReturn(false);
        assertThatThrownBy(() -> service.getAudit(id))
                .isInstanceOf(DomainException.NotFoundError.class);
    }

    @Test
    void getAuditReturnsOrderedEntries() {
        UUID claimId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Instant t1 = Instant.parse("2025-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2025-01-01T11:00:00Z");
        List<AuditEntry> ordered = List.of(
                new AuditEntry(UUID.randomUUID(), claimId, null, ClaimState.EINGEREICHT,
                        actor, Role.ANSPRUCHSTELLER, null, t1),
                new AuditEntry(UUID.randomUUID(), claimId, ClaimState.EINGEREICHT, ClaimState.IN_PRUEFUNG,
                        actor, Role.SACHBEARBEITER, null, t2)
        );
        when(claimRepository.existsById(claimId)).thenReturn(true);
        when(auditRepository.findByClaimIdOrderByOccurredAtAsc(claimId)).thenReturn(ordered);

        List<AuditEntry> result = service.getAudit(claimId);

        assertThat(result).containsExactlyElementsOf(ordered);
    }
}
