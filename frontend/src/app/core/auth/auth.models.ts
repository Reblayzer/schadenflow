import { Role } from '../models/claim.models';

export interface AuthUser {
  token: string;
  username: string;
  role: Role;
  expiresAt: string; // ISO instant
}
