import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

@Injectable({ providedIn: 'root' })
export class NotifyService {
  private readonly snackBar = inject(MatSnackBar);

  error(message: string): void {
    this.snackBar.open(message, 'OK', { duration: 5000, panelClass: 'snack-error' });
  }

  success(message: string): void {
    this.snackBar.open(message, 'OK', { duration: 3000 });
  }
}
