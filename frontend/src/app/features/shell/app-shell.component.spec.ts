import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { AppShellComponent } from './app-shell.component';
import { AuthService } from '../../core/auth/auth.service';
import { Role } from '../../core/models/claim.models';

describe('AppShellComponent', () => {
  let fixture: ComponentFixture<AppShellComponent>;
  let auth: Partial<AuthService>;

  function setup(role: Role) {
    auth = {
      currentUser: signal({ token: 't', username: 'u', role, expiresAt: '' }) as any,
      role: signal(role) as any,
      logout: jasmine.createSpy('logout'),
    };
    TestBed.configureTestingModule({
      imports: [AppShellComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();
  }

  it('shows the "Neuer Schaden" action for a claimant', () => {
    setup(Role.ANSPRUCHSTELLER);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Neuer Schaden');
  });

  it('hides the "Neuer Schaden" action for a reviewer', () => {
    setup(Role.SACHBEARBEITER);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('Neuer Schaden');
  });

  it('calls logout when the logout button is clicked', () => {
    setup(Role.ADMIN);
    const btn = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-test="logout"]',
    ) as HTMLButtonElement;
    btn.click();
    expect(auth.logout).toHaveBeenCalled();
  });
});
