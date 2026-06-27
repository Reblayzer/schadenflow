import { ApiClientError } from '../core/api/api-error';
import { Category, ClaimState, MissingInfoFlag, Role } from '../core/models/claim.models';

const STATE_LABELS: Record<ClaimState, string> = {
  [ClaimState.EINGEREICHT]: 'Eingereicht',
  [ClaimState.IN_PRUEFUNG]: 'In Prüfung',
  [ClaimState.GENEHMIGT]: 'Genehmigt',
  [ClaimState.ABGELEHNT]: 'Abgelehnt',
  [ClaimState.AUSBEZAHLT]: 'Ausbezahlt',
};

const STATE_COLORS: Record<ClaimState, 'primary' | 'accent' | 'warn' | ''> = {
  [ClaimState.EINGEREICHT]: 'accent',
  [ClaimState.IN_PRUEFUNG]: 'primary',
  [ClaimState.GENEHMIGT]: 'primary',
  [ClaimState.ABGELEHNT]: 'warn',
  [ClaimState.AUSBEZAHLT]: '',
};

const CATEGORY_LABELS: Record<Category, string> = {
  [Category.ARZTKOSTEN]: 'Arztkosten',
  [Category.MEDIKAMENTE]: 'Medikamente',
  [Category.SPITAL]: 'Spital',
  [Category.ZAHNARZT]: 'Zahnarzt',
  [Category.THERAPIE]: 'Therapie',
  [Category.HILFSMITTEL]: 'Hilfsmittel',
  [Category.SONSTIGES]: 'Sonstiges',
};

const FLAG_LABELS: Record<MissingInfoFlag, string> = {
  [MissingInfoFlag.MISSING_AMOUNT]: 'Betrag fehlt',
  [MissingInfoFlag.VAGUE_DESCRIPTION]: 'Vage Beschreibung',
  [MissingInfoFlag.MISSING_DATE]: 'Datum fehlt',
  [MissingInfoFlag.MISSING_PROVIDER]: 'Leistungserbringer fehlt',
};

const ERROR_MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'Benutzername oder Passwort ist falsch.',
  UNAUTHORIZED: 'Bitte melden Sie sich an.',
  FORBIDDEN: 'Keine Berechtigung für diese Aktion.',
  NOT_FOUND: 'Der Schadenfall wurde nicht gefunden.',
  ILLEGAL_TRANSITION: 'Dieser Statuswechsel ist nicht erlaubt.',
  VALIDATION_ERROR: 'Die Eingaben sind ungültig.',
  TRIAGE_UNAVAILABLE: 'Die KI-Triage ist derzeit nicht verfügbar.',
  NETWORK: 'Netzwerkfehler — der Server ist nicht erreichbar.',
};

export const ALL_CATEGORIES: Category[] = Object.values(Category);
export const ALL_STATES: ClaimState[] = Object.values(ClaimState);

export function claimStateLabel(s: ClaimState): string {
  return STATE_LABELS[s] ?? s;
}
export function claimStateColor(s: ClaimState): 'primary' | 'accent' | 'warn' | '' {
  return STATE_COLORS[s] ?? '';
}
export function categoryLabel(c: Category): string {
  return CATEGORY_LABELS[c] ?? c;
}
export function flagLabel(f: MissingInfoFlag): string {
  return FLAG_LABELS[f] ?? f;
}
export function roleLabel(r: Role): string {
  return r.charAt(0) + r.slice(1).toLowerCase();
}
export function errorMessage(err: unknown): string {
  if (err instanceof ApiClientError) {
    return ERROR_MESSAGES[err.code] ?? err.message ?? 'Ein Fehler ist aufgetreten.';
  }
  return 'Ein unerwarteter Fehler ist aufgetreten.';
}
