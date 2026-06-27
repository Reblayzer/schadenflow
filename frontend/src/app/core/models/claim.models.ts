export enum ClaimState {
  EINGEREICHT = 'EINGEREICHT',
  IN_PRUEFUNG = 'IN_PRUEFUNG',
  GENEHMIGT = 'GENEHMIGT',
  ABGELEHNT = 'ABGELEHNT',
  AUSBEZAHLT = 'AUSBEZAHLT',
}

export enum Category {
  ARZTKOSTEN = 'ARZTKOSTEN',
  MEDIKAMENTE = 'MEDIKAMENTE',
  SPITAL = 'SPITAL',
  ZAHNARZT = 'ZAHNARZT',
  THERAPIE = 'THERAPIE',
  HILFSMITTEL = 'HILFSMITTEL',
  SONSTIGES = 'SONSTIGES',
}

export enum Role {
  ANSPRUCHSTELLER = 'ANSPRUCHSTELLER',
  SACHBEARBEITER = 'SACHBEARBEITER',
  ADMIN = 'ADMIN',
}

export enum MissingInfoFlag {
  MISSING_AMOUNT = 'MISSING_AMOUNT',
  VAGUE_DESCRIPTION = 'VAGUE_DESCRIPTION',
  MISSING_DATE = 'MISSING_DATE',
  MISSING_PROVIDER = 'MISSING_PROVIDER',
}

export interface Claim {
  id: string;
  claimantId: string;
  title: string;
  description: string;
  category: Category | null;
  amount: number;
  state: ClaimState;
  triageSummary: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AuditEntry {
  id: string;
  claimId: string;
  fromState: ClaimState | null;
  toState: ClaimState;
  actorId: string;
  actorRole: Role;
  reason: string | null;
  occurredAt: string;
}

export interface TriageResult {
  summary: string;
  suggestedCategory: Category;
  missingInfoFlags: MissingInfoFlag[];
}
