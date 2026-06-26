package ch.sumex.schadenflow.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ch.sumex.schadenflow.audit.AuditEntry;
import ch.sumex.schadenflow.audit.AuditRepository;
import ch.sumex.schadenflow.shared.DomainException;
import ch.sumex.schadenflow.triage.TriageInput;
import ch.sumex.schadenflow.triage.TriageResult;
import ch.sumex.schadenflow.triage.TriageService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final AuditRepository auditRepository;
    private final ClaimStateMachine stateMachine;
    private final TriageService triageService;

    public ClaimService(ClaimRepository claimRepository, AuditRepository auditRepository,
                        ClaimStateMachine stateMachine, TriageService triageService) {
        this.claimRepository = claimRepository;
        this.auditRepository = auditRepository;
        this.stateMachine = stateMachine;
        this.triageService = triageService;
    }

    @Transactional
    public Claim create(String title, String description, BigDecimal amount,
                        UUID actorId, Role actorRole) {
        Instant now = Instant.now();
        Claim claim = new Claim(UUID.randomUUID(), actorId, title, description, null, amount,
                ClaimState.EINGEREICHT, now, now);
        Claim saved = claimRepository.save(claim);
        auditRepository.save(new AuditEntry(UUID.randomUUID(), saved.getId(), null,
                ClaimState.EINGEREICHT, actorId, actorRole, null, now));
        return saved;
    }

    @Transactional
    public Claim transition(UUID claimId, ClaimState targetState, String reason,
                            UUID actorId, Role actorRole) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new DomainException.NotFoundError("Claim %s not found".formatted(claimId)));
        assertCanAccess(claim, actorId, actorRole);
        ClaimState from = claim.getState();
        stateMachine.validateTransition(from, targetState, actorRole);
        if (targetState == ClaimState.ABGELEHNT && (reason == null || reason.isBlank())) {
            throw new DomainException.ValidationError("A reason is required when rejecting a claim");
        }
        Instant now = Instant.now();
        claim.setState(targetState);
        claim.setUpdatedAt(now);
        Claim saved = claimRepository.save(claim);
        auditRepository.save(new AuditEntry(UUID.randomUUID(), saved.getId(), from, targetState,
                actorId, actorRole, reason, now));
        return saved;
    }

    @Transactional(readOnly = true)
    public Claim getById(UUID claimId, UUID actorId, Role actorRole) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new DomainException.NotFoundError("Claim %s not found".formatted(claimId)));
        assertCanAccess(claim, actorId, actorRole);
        return claim;
    }

    @Transactional(readOnly = true)
    public Page<Claim> list(ClaimState state, UUID claimantId, UUID actorId, Role actorRole,
                            Pageable pageable) {
        UUID effectiveClaimant = actorRole == Role.ANSPRUCHSTELLER ? actorId : claimantId;
        if (state != null && effectiveClaimant != null) {
            return claimRepository.findByStateAndClaimantId(state, effectiveClaimant, pageable);
        }
        if (state != null) {
            return claimRepository.findByState(state, pageable);
        }
        if (effectiveClaimant != null) {
            return claimRepository.findByClaimantId(effectiveClaimant, pageable);
        }
        return claimRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> getAudit(UUID claimId, UUID actorId, Role actorRole) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new DomainException.NotFoundError("Claim %s not found".formatted(claimId)));
        assertCanAccess(claim, actorId, actorRole);
        return auditRepository.findByClaimIdOrderByOccurredAtAsc(claimId);
    }

    @Transactional(readOnly = true)
    public TriageResult triage(UUID claimId, UUID actorId, Role actorRole) {
        assertReviewer(actorRole);
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new DomainException.NotFoundError("Claim %s not found".formatted(claimId)));
        if (claim.getState() != ClaimState.EINGEREICHT && claim.getState() != ClaimState.IN_PRUEFUNG) {
            throw new DomainException.ValidationError("Triage nur vor der Entscheidung möglich");
        }
        return triageService.triage(new TriageInput(claim.getTitle(), claim.getDescription(), claim.getAmount()));
    }

    @Transactional
    public Claim updateCategory(UUID claimId, Category category, String triageSummary,
                                UUID actorId, Role actorRole) {
        assertReviewer(actorRole);
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new DomainException.NotFoundError("Claim %s not found".formatted(claimId)));
        claim.setCategory(category);
        claim.setTriageSummary(triageSummary);
        claim.setUpdatedAt(Instant.now());
        return claimRepository.save(claim);
    }

    private void assertReviewer(Role actorRole) {
        if (actorRole != Role.SACHBEARBEITER && actorRole != Role.ADMIN) {
            throw new DomainException.ForbiddenError("Nur Sachbearbeiter oder Admin dürfen diese Aktion ausführen");
        }
    }

    private void assertCanAccess(Claim claim, UUID actorId, Role actorRole) {
        if (actorRole == Role.ANSPRUCHSTELLER && !claim.getClaimantId().equals(actorId)) {
            throw new DomainException.ForbiddenError("You may only access your own claims");
        }
    }
}
