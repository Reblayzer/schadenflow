package ch.sumex.schadenflow.claim;

import java.util.List;
import java.util.UUID;

import ch.sumex.schadenflow.auth.AuthenticatedUser;
import ch.sumex.schadenflow.claim.dto.AuditEntryResponse;
import ch.sumex.schadenflow.claim.dto.ClaimResponse;
import ch.sumex.schadenflow.claim.dto.CreateClaimRequest;
import ch.sumex.schadenflow.claim.dto.TransitionRequest;
import ch.sumex.schadenflow.claim.dto.TriageResponse;
import ch.sumex.schadenflow.claim.dto.UpdateClaimRequest;
import ch.sumex.schadenflow.shared.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
            @AuthenticationPrincipal AuthenticatedUser actor) {
        Claim claim = service.create(request.title(), request.description(), request.amount(),
                actor.userId(), actor.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(ClaimResponse.from(claim)));
    }

    @GetMapping
    public ApiResponse<Page<ClaimResponse>> list(
            @RequestParam(required = false) ClaimState state,
            @RequestParam(required = false) UUID claimantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUser actor) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        Page<ClaimResponse> result = service.list(state, claimantId, actor.userId(), actor.role(), pageable)
                .map(ClaimResponse::from);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<ClaimResponse> getById(@PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser actor) {
        return ApiResponse.ok(ClaimResponse.from(service.getById(id, actor.userId(), actor.role())));
    }

    @PostMapping("/{id}/transitions")
    public ApiResponse<ClaimResponse> transition(
            @PathVariable UUID id,
            @Valid @RequestBody TransitionRequest request,
            @AuthenticationPrincipal AuthenticatedUser actor) {
        Claim claim = service.transition(id, request.targetState(), request.reason(),
                actor.userId(), actor.role());
        return ApiResponse.ok(ClaimResponse.from(claim));
    }

    @GetMapping("/{id}/audit")
    public ApiResponse<List<AuditEntryResponse>> audit(@PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser actor) {
        List<AuditEntryResponse> entries = service.getAudit(id, actor.userId(), actor.role()).stream()
                .map(AuditEntryResponse::from).toList();
        return ApiResponse.ok(entries);
    }

    @PostMapping("/{id}/triage")
    public ApiResponse<TriageResponse> triage(@PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser actor) {
        return ApiResponse.ok(TriageResponse.from(service.triage(id, actor.userId(), actor.role())));
    }

    @PatchMapping("/{id}")
    public ApiResponse<ClaimResponse> updateCategory(@PathVariable UUID id,
            @Valid @RequestBody UpdateClaimRequest request,
            @AuthenticationPrincipal AuthenticatedUser actor) {
        Claim claim = service.updateCategory(id, request.category(), request.triageSummary(),
                actor.userId(), actor.role());
        return ApiResponse.ok(ClaimResponse.from(claim));
    }
}
