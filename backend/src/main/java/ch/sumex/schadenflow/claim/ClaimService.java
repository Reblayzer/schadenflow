package ch.sumex.schadenflow.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ch.sumex.schadenflow.audit.AuditEntry;
import ch.sumex.schadenflow.audit.AuditRepository;
import ch.sumex.schadenflow.shared.DomainException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final AuditRepository auditRepository;
    private final ClaimStateMachine stateMachine;

    public ClaimService(ClaimRepository claimRepository, AuditRepository auditRepository,
                        ClaimStateMachine stateMachine) {
        this.claimRepository = claimRepository;
        this.auditRepository = auditRepository;
        this.stateMachine = stateMachine;
    }

    @Transactional
    public Claim create(UUID claimantId, String title, String description, BigDecimal amount,
                        UUID actorId, Role actorRole) {
        Instant now = Instant.now();
        Claim claim = new Claim(UUID.randomUUID(), claimantId, title, description, null, amount,
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
    public Claim getById(UUID claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new DomainException.NotFoundError("Claim %s not found".formatted(claimId)));
    }

    @Transactional(readOnly = true)
    public Page<Claim> list(ClaimState state, UUID claimantId, Pageable pageable) {
        if (state != null && claimantId != null) {
            return claimRepository.findByStateAndClaimantId(state, claimantId, pageable);
        }
        if (state != null) {
            return claimRepository.findByState(state, pageable);
        }
        if (claimantId != null) {
            return claimRepository.findByClaimantId(claimantId, pageable);
        }
        return claimRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> getAudit(UUID claimId) {
        if (!claimRepository.existsById(claimId)) {
            throw new DomainException.NotFoundError("Claim %s not found".formatted(claimId));
        }
        return auditRepository.findByClaimIdOrderByOccurredAtAsc(claimId);
    }
}
