package ch.sumex.schadenflow.audit;

import ch.sumex.schadenflow.claim.ClaimState;
import ch.sumex.schadenflow.claim.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_entries")
public class AuditEntry {

    @Id
    private UUID id;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state")
    private ClaimState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false)
    private ClaimState toState;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false)
    private Role actorRole;

    @Column
    private String reason;

    @Column(nullable = false)
    private Instant timestamp;

    protected AuditEntry() { }

    public AuditEntry(UUID id, UUID claimId, ClaimState fromState, ClaimState toState,
                      UUID actorId, Role actorRole, String reason, Instant timestamp) {
        this.id = id;
        this.claimId = claimId;
        this.fromState = fromState;
        this.toState = toState;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.reason = reason;
        this.timestamp = timestamp;
    }

    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public ClaimState getFromState() { return fromState; }
    public ClaimState getToState() { return toState; }
    public UUID getActorId() { return actorId; }
    public Role getActorRole() { return actorRole; }
    public String getReason() { return reason; }
    public Instant getTimestamp() { return timestamp; }
}
