import { ClaimState, Role, Category, MissingInfoFlag } from '../core/models/claim.models';
import { ApiClientError } from '../core/api/api-error';
import { claimStateLabel, claimStateColor, categoryLabel, flagLabel, errorMessage } from './claim-labels';
import { availableTransitions } from './transitions';

describe('claim-labels', () => {
  it('maps states to German labels', () => {
    expect(claimStateLabel(ClaimState.IN_PRUEFUNG)).toBe('In Prüfung');
    expect(claimStateLabel(ClaimState.AUSBEZAHLT)).toBe('Ausbezahlt');
  });

  it('maps categories to labels', () => {
    expect(categoryLabel(Category.ZAHNARZT)).toBe('Zahnarzt');
  });

  it('maps known error codes to friendly messages', () => {
    expect(errorMessage(new ApiClientError('INVALID_CREDENTIALS', 'x'))).toContain('Passwort');
    expect(errorMessage(new ApiClientError('TRIAGE_UNAVAILABLE', 'x'))).toContain('KI-Triage');
  });

  it('maps states to Material palette colors', () => {
    expect(claimStateColor(ClaimState.ABGELEHNT)).toBe('warn');
    expect(claimStateColor(ClaimState.IN_PRUEFUNG)).toBe('primary');
    expect(claimStateColor(ClaimState.AUSBEZAHLT)).toBe('');
  });

  it('maps missing-info flags to German labels', () => {
    expect(flagLabel(MissingInfoFlag.MISSING_AMOUNT)).toBe('Betrag fehlt');
  });
});

describe('availableTransitions', () => {
  it('offers In-Prüfung for a reviewer on EINGEREICHT', () => {
    const t = availableTransitions(ClaimState.EINGEREICHT, Role.SACHBEARBEITER);
    expect(t.map((o) => o.target)).toEqual([ClaimState.IN_PRUEFUNG]);
  });

  it('offers approve+reject (reason) for a reviewer on IN_PRUEFUNG', () => {
    const t = availableTransitions(ClaimState.IN_PRUEFUNG, Role.SACHBEARBEITER);
    expect(t.map((o) => o.target).sort()).toEqual([ClaimState.ABGELEHNT, ClaimState.GENEHMIGT].sort());
    expect(t.find((o) => o.target === ClaimState.ABGELEHNT)!.requiresReason).toBeTrue();
  });

  it('offers Auszahlen only for an admin on GENEHMIGT', () => {
    expect(availableTransitions(ClaimState.GENEHMIGT, Role.SACHBEARBEITER)).toEqual([]);
    expect(availableTransitions(ClaimState.GENEHMIGT, Role.ADMIN).map((o) => o.target)).toEqual([
      ClaimState.AUSBEZAHLT,
    ]);
  });

  it('offers nothing to a claimant', () => {
    expect(availableTransitions(ClaimState.EINGEREICHT, Role.ANSPRUCHSTELLER)).toEqual([]);
  });
});
