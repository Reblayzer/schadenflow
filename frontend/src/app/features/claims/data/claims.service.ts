import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApiResponse, Page } from '../../../core/api/api-response.model';
import { unwrap } from '../../../core/api/unwrap';
import { AuditEntry, Category, Claim, ClaimState, TriageResult } from '../../../core/models/claim.models';

export interface ClaimFilters {
  state?: ClaimState;
  claimantId?: string;
}

const BASE = '/api/claims';

@Injectable({ providedIn: 'root' })
export class ClaimsService {
  private readonly http = inject(HttpClient);

  list(filters: ClaimFilters, page: number, size: number): Observable<Page<Claim>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (filters.state) {
      params = params.set('state', filters.state);
    }
    if (filters.claimantId) {
      params = params.set('claimantId', filters.claimantId);
    }
    return this.http.get<ApiResponse<Page<Claim>>>(BASE, { params }).pipe(unwrap<Page<Claim>>());
  }

  getById(id: string): Observable<Claim> {
    return this.http.get<ApiResponse<Claim>>(`${BASE}/${id}`).pipe(unwrap<Claim>());
  }

  create(req: { title: string; description: string; amount: number }): Observable<Claim> {
    return this.http.post<ApiResponse<Claim>>(BASE, req).pipe(unwrap<Claim>());
  }

  transition(id: string, targetState: ClaimState, reason?: string): Observable<Claim> {
    return this.http
      .post<ApiResponse<Claim>>(`${BASE}/${id}/transitions`, { targetState, reason })
      .pipe(unwrap<Claim>());
  }

  triage(id: string): Observable<TriageResult> {
    return this.http.post<ApiResponse<TriageResult>>(`${BASE}/${id}/triage`, {}).pipe(unwrap<TriageResult>());
  }

  confirmCategory(id: string, category: Category, triageSummary?: string): Observable<Claim> {
    return this.http
      .patch<ApiResponse<Claim>>(`${BASE}/${id}`, { category, triageSummary })
      .pipe(unwrap<Claim>());
  }

  audit(id: string): Observable<AuditEntry[]> {
    return this.http.get<ApiResponse<AuditEntry[]>>(`${BASE}/${id}/audit`).pipe(unwrap<AuditEntry[]>());
  }
}
