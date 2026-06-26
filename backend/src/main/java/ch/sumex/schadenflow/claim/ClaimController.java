package ch.sumex.schadenflow.claim;

import java.util.List;
import java.util.UUID;

import ch.sumex.schadenflow.claim.dto.AuditEntryResponse;
import ch.sumex.schadenflow.claim.dto.ClaimResponse;
import ch.sumex.schadenflow.claim.dto.CreateClaimRequest;
import ch.sumex.schadenflow.claim.dto.TransitionRequest;
import ch.sumex.schadenflow.shared.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/claims")
public class ClaimController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ClaimResponse>> create(
            @Valid @RequestBody CreateClaimRequest request,
            @RequestHeader("X-Actor-Id") UUID actorId,
            @RequestHeader("X-Actor-Role") Role actorRole) {
        Claim claim = service.create(request.claimantId(), request.title(), request.description(),
                request.amount(), actorId, actorRole);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(ClaimResponse.from(claim)));
    }

    @GetMapping
    public ApiResponse<Page<ClaimResponse>> list(
            @RequestParam(required = false) ClaimState state,
            @RequestParam(required = false) UUID claimantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        Page<ClaimResponse> result = service.list(state, claimantId, pageable).map(ClaimResponse::from);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<ClaimResponse> getById(@PathVariable UUID id) {
        return ApiResponse.ok(ClaimResponse.from(service.getById(id)));
    }

    @PostMapping("/{id}/transitions")
    public ApiResponse<ClaimResponse> transition(
            @PathVariable UUID id,
            @Valid @RequestBody TransitionRequest request,
            @RequestHeader("X-Actor-Id") UUID actorId,
            @RequestHeader("X-Actor-Role") Role actorRole) {
        Claim claim = service.transition(id, request.targetState(), request.reason(), actorId, actorRole);
        return ApiResponse.ok(ClaimResponse.from(claim));
    }

    @GetMapping("/{id}/audit")
    public ApiResponse<List<AuditEntryResponse>> audit(@PathVariable UUID id) {
        List<AuditEntryResponse> entries = service.getAudit(id).stream()
                .map(AuditEntryResponse::from).toList();
        return ApiResponse.ok(entries);
    }
}
