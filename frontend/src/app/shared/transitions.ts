import { ClaimState, Role } from '../core/models/claim.models';

export interface TransitionOption {
  target: ClaimState;
  label: string;
  requiresReason: boolean;
}

interface Edge {
  from: ClaimState;
  to: ClaimState;
  roles: Role[];
  label: string;
  requiresReason: boolean;
}

const EDGES: Edge[] = [
  { from: ClaimState.EINGEREICHT, to: ClaimState.IN_PRUEFUNG, roles: [Role.SACHBEARBEITER, Role.ADMIN], label: 'In Prüfung nehmen', requiresReason: false },
  { from: ClaimState.IN_PRUEFUNG, to: ClaimState.GENEHMIGT, roles: [Role.SACHBEARBEITER, Role.ADMIN], label: 'Genehmigen', requiresReason: false },
  { from: ClaimState.IN_PRUEFUNG, to: ClaimState.ABGELEHNT, roles: [Role.SACHBEARBEITER, Role.ADMIN], label: 'Ablehnen', requiresReason: true },
  { from: ClaimState.GENEHMIGT, to: ClaimState.AUSBEZAHLT, roles: [Role.ADMIN], label: 'Auszahlen', requiresReason: false },
];

export function availableTransitions(from: ClaimState, role: Role): TransitionOption[] {
  return EDGES.filter((e) => e.from === from && e.roles.includes(role)).map((e) => ({
    target: e.to,
    label: e.label,
    requiresReason: e.requiresReason,
  }));
}

