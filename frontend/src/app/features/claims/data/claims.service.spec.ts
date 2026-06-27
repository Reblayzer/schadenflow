import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ClaimsService } from './claims.service';
import { Category, ClaimState } from '../../../core/models/claim.models';

describe('ClaimsService', () => {
  let service: ClaimsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ClaimsService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ClaimsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('list builds query params and unwraps the page', () => {
    service.list({ state: ClaimState.EINGEREICHT }, 0, 20).subscribe();
    const req = http.expectOne(
      (r) => r.url === '/api/claims' && r.params.get('state') === 'EINGEREICHT' &&
        r.params.get('page') === '0' && r.params.get('size') === '20',
    );
    expect(req.request.method).toBe('GET');
    req.flush({ ok: true, data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } });
  });

  it('create posts the body and unwraps the claim', () => {
    let claim: unknown;
    service.create({ title: 't', description: 'd', amount: 10 }).subscribe((c) => (claim = c));
    const req = http.expectOne('/api/claims');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ title: 't', description: 'd', amount: 10 });
    req.flush({ ok: true, data: { id: '1', title: 't' } });
    expect((claim as any).id).toBe('1');
  });

  it('transition posts targetState and reason', () => {
    service.transition('1', ClaimState.ABGELEHNT, 'nope').subscribe();
    const req = http.expectOne('/api/claims/1/transitions');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ targetState: 'ABGELEHNT', reason: 'nope' });
    req.flush({ ok: true, data: { id: '1' } });
  });

  it('triage posts to the triage endpoint', () => {
    service.triage('1').subscribe();
    const req = http.expectOne('/api/claims/1/triage');
    expect(req.request.method).toBe('POST');
    req.flush({ ok: true, data: { summary: 's', suggestedCategory: 'ZAHNARZT', missingInfoFlags: [] } });
  });

  it('confirmCategory PATCHes category and summary', () => {
    service.confirmCategory('1', Category.ZAHNARZT, 'sum').subscribe();
    const req = http.expectOne('/api/claims/1');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ category: 'ZAHNARZT', triageSummary: 'sum' });
    req.flush({ ok: true, data: { id: '1' } });
  });

  it('audit GETs the audit list', () => {
    service.audit('1').subscribe();
    const req = http.expectOne('/api/claims/1/audit');
    expect(req.request.method).toBe('GET');
    req.flush({ ok: true, data: [] });
  });
});
