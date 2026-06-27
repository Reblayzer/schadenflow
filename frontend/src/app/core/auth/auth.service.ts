import { Injectable, computed, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { ApiResponse } from '../api/api-response.model';
import { unwrap } from '../api/unwrap';
import { Role } from '../models/claim.models';
import { AuthUser } from './auth.models';

const STORAGE_KEY = 'schadenflow.auth';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly _currentUser = signal<AuthUser | null>(this.loadValidSession());

  readonly currentUser = this._currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this._currentUser() !== null);
  readonly role = computed(() => this._currentUser()?.role ?? null);

  // HttpClient may be undefined when constructed via `new` in a unit test that
  // only exercises hydration/logout; guard the login() path accordingly.
  constructor(private readonly http?: HttpClient) {}

  login(username: string, password: string): Observable<AuthUser> {
    return this.http!.post<ApiResponse<AuthUser>>('/api/auth/login', { username, password }).pipe(
      unwrap<AuthUser>(),
      tap((user) => this.setSession(user)),
    );
  }

  logout(): void {
    localStorage.removeItem(STORAGE_KEY);
    this._currentUser.set(null);
  }

  token(): string | null {
    return this._currentUser()?.token ?? null;
  }

  private setSession(user: AuthUser): void {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(user));
    this._currentUser.set(user);
  }

  private loadValidSession(): AuthUser | null {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    try {
      const user = JSON.parse(raw) as AuthUser;
      if (!user.expiresAt || new Date(user.expiresAt).getTime() <= Date.now()) {
        localStorage.removeItem(STORAGE_KEY);
        return null;
      }
      return user;
    } catch {
      localStorage.removeItem(STORAGE_KEY);
      return null;
    }
  }
}
