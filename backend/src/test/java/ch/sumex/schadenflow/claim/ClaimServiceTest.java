package ch.sumex.schadenflow.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ch.sumex.schadenflow.audit.AuditEntry;
import ch.sumex.schadenflow.audit.AuditRepository;
import ch.sumex.schadenflow.shared.DomainException;
import ch.sumex.schadenflow.triage.MockTriageService;
import ch.sumex.schadenflow.triage.TriageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    @Mock ClaimRepository claimRepository;
    @Mock AuditRepository auditRepository;
    @Spy ClaimStateMachine stateMachine = new ClaimStateMachine();
    @Spy MockTriageService triageService = new MockTriageService();
    @InjectMocks ClaimService service;

    private Claim sampleClaim(ClaimState state) {
        return new Claim(UUID.randomUUID(), UUID.randomUUID(), "t", "d", null,
                new BigDecimal("10.00"), state, Instant.now(), Instant.now());
    }

    private Claim sampleClaim(UUID id, ClaimState state) {
        return new Claim(id, UUID.randomUUID(), "t", "d", null,
                new BigDecimal("10.00"), state, Instant.now(), Instant.now());
    }

    @Test
    void createPersistsClaimAndCreationAuditRow() {
        UUID actor = UUID.randomUUID();
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        Claim result = service.create("Broken arm", "Fell", new BigDecimal("250.00"),
                actor, Role.ANSPRUCHSTELLER);

        assertThat(result.getState()).isEqualTo(ClaimState.EINGEREICHT);
        assertThat(result.getClaimantId()).isEqualTo(actor);
        ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditRepository).save(audit.capture());
        assertThat(audit.getValue().getFromState()).isNull();
        assertThat(audit.getValue().getToState()).isEqualTo(ClaimState.EINGEREICHT);
        assertThat(audit.getValue().getActorId()).isEqualTo(actor);
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
        assertThatThrownBy(() -> service.getById(id, UUID.randomUUID(), Role.SACHBEARBEITER))
                .isInstanceOf(DomainException.NotFoundError.class);
    }

    @Test
    void getAuditThrowsNotFoundWhenClaimAbsent() {
        UUID id = UUID.randomUUID();
        when(claimRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getAudit(id, UUID.randomUUID(), Role.SACHBEARBEITER))
                .isInstanceOf(DomainException.NotFoundError.class);
    }

    @Test
    void getAuditReturnsOrderedEntries() {
        UUID claimId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Instant t1 = Instant.parse("2025-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2025-01-01T11:00:00Z");
        Claim claimForAudit = new Claim(claimId, UUID.randomUUID(), "t", "d", null,
                new BigDecimal("10.00"), ClaimState.EINGEREICHT, Instant.now(), Instant.now());
        when(claimRepository.findById(claimId)).thenReturn(Optional.of(claimForAudit));
        List<AuditEntry> ordered = List.of(
                new AuditEntry(UUID.randomUUID(), claimId, null, ClaimState.EINGEREICHT,
                        actor, Role.ANSPRUCHSTELLER, null, t1),
                new AuditEntry(UUID.randomUUID(), claimId, ClaimState.EINGEREICHT, ClaimState.IN_PRUEFUNG,
                        actor, Role.SACHBEARBEITER, null, t2)
        );
        when(auditRepository.findByClaimIdOrderByOccurredAtAsc(claimId)).thenReturn(ordered);

        List<AuditEntry> result = service.getAudit(claimId, actor, Role.SACHBEARBEITER);

        assertThat(result).containsExactlyElementsOf(ordered);
    }

    // --- Ownership tests ---

    @Test
    void claimantCannotAccessAnotherUsersClaim() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Claim claim = new Claim(UUID.randomUUID(), owner, "Brille", "Neue Brille", null,
                new BigDecimal("250.00"), ClaimState.EINGEREICHT, Instant.now(), Instant.now());
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        assertThatThrownBy(() -> service.getById(claim.getId(), stranger, Role.ANSPRUCHSTELLER))
                .isInstanceOf(DomainException.ForbiddenError.class);
    }

    @Test
    void caseworkerCanAccessAnyClaim() {
        UUID owner = UUID.randomUUID();
        Claim claim = new Claim(UUID.randomUUID(), owner, "Brille", "Neue Brille", null,
                new BigDecimal("250.00"), ClaimState.EINGEREICHT, Instant.now(), Instant.now());
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        Claim seen = service.getById(claim.getId(), UUID.randomUUID(), Role.SACHBEARBEITER);
        assertThat(seen.getId()).isEqualTo(claim.getId());
    }

    @Test
    void listForClaimantIsForcedToOwnClaims() {
        UUID owner = UUID.randomUUID();
        Claim ownerClaim = new Claim(UUID.randomUUID(), owner, "A", "desc", null,
                new BigDecimal("10.00"), ClaimState.EINGEREICHT, Instant.now(), Instant.now());
        Page<Claim> ownerPage = new PageImpl<>(List.of(ownerClaim));
        when(claimRepository.findByClaimantId(eq(owner), any())).thenReturn(ownerPage);
        // claimant passes someone else's id but only sees their own
        Page<Claim> page = service.list(null, UUID.randomUUID(), owner, Role.ANSPRUCHSTELLER,
                PageRequest.of(0, 20));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getClaimantId()).isEqualTo(owner);
    }

    // --- Triage and updateCategory tests ---

    @Test
    void triageReturnsSuggestionForReviewer() {
        UUID claimId = UUID.randomUUID();
        Claim claim = sampleClaim(claimId, ClaimState.EINGEREICHT);
        when(claimRepository.findById(claimId)).thenReturn(Optional.of(claim));

        TriageResult result = service.triage(claimId, UUID.randomUUID(), Role.SACHBEARBEITER);

        assertThat(result.summary()).isNotBlank();
        assertThat(result.suggestedCategory()).isNotNull();
    }

    @Test
    void triageDeniedForClaimant() {
        UUID claimId = UUID.randomUUID();
        assertThatThrownBy(() -> service.triage(claimId, UUID.randomUUID(), Role.ANSPRUCHSTELLER))
                .isInstanceOf(DomainException.ForbiddenError.class);
    }

    @Test
    void triageRejectedAfterDecision() {
        UUID claimId = UUID.randomUUID();
        Claim claim = sampleClaim(claimId, ClaimState.GENEHMIGT);
        when(claimRepository.findById(claimId)).thenReturn(Optional.of(claim));
        assertThatThrownBy(() -> service.triage(claimId, UUID.randomUUID(), Role.SACHBEARBEITER))
                .isInstanceOf(DomainException.ValidationError.class);
    }

    @Test
    void updateCategoryAppliesValuesForReviewer() {
        UUID claimId = UUID.randomUUID();
        Claim claim = sampleClaim(claimId, ClaimState.IN_PRUEFUNG);
        when(claimRepository.findById(claimId)).thenReturn(Optional.of(claim));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Claim updated = service.updateCategory(claimId, Category.ZAHNARZT, "Zusammenfassung",
                UUID.randomUUID(), Role.SACHBEARBEITER);

        assertThat(updated.getCategory()).isEqualTo(Category.ZAHNARZT);
        assertThat(updated.getTriageSummary()).isEqualTo("Zusammenfassung");
    }

    @Test
    void updateCategoryDeniedForClaimant() {
        UUID claimId = UUID.randomUUID();
        assertThatThrownBy(() -> service.updateCategory(claimId, Category.ZAHNARZT, null,
                UUID.randomUUID(), Role.ANSPRUCHSTELLER))
                .isInstanceOf(DomainException.ForbiddenError.class);
    }
}
