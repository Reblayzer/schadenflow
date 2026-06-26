package ch.sumex.schadenflow.claim.dto;

import ch.sumex.schadenflow.audit.AuditEntry;
import ch.sumex.schadenflow.claim.ClaimState;
import ch.sumex.schadenflow.claim.Role;
import java.time.Instant;
import java.util.UUID;

public record AuditEntryResponse(
        UUID id,
        UUID claimId,
        ClaimState fromState,
        ClaimState toState,
        UUID actorId,
        Role actorRole,
        String reason,
        Instant occurredAt
) {
    public static AuditEntryResponse from(AuditEntry a) {
        return new AuditEntryResponse(a.getId(), a.getClaimId(), a.getFromState(), a.getToState(),
                a.getActorId(), a.getActorRole(), a.getReason(), a.getOccurredAt());
    }
}
