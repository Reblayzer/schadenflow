import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { ConfirmDialogComponent } from './confirm-dialog.component';

describe('ConfirmDialogComponent', () => {
  let fixture: ComponentFixture<ConfirmDialogComponent>;
  let component: ConfirmDialogComponent;
  let dialogRef: jasmine.SpyObj<MatDialogRef<ConfirmDialogComponent>>;

  beforeEach(async () => {
    dialogRef = jasmine.createSpyObj<MatDialogRef<ConfirmDialogComponent>>('MatDialogRef', ['close']);
    await TestBed.configureTestingModule({
      imports: [ConfirmDialogComponent],
      providers: [
        provideAnimations(),
        { provide: MAT_DIALOG_DATA, useValue: { title: 'Confirm', message: 'Sure?', requireReason: true } },
        { provide: MatDialogRef, useValue: dialogRef },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ConfirmDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('cancel() closes with confirmed: false', () => {
    component.cancel();
    expect(dialogRef.close).toHaveBeenCalledWith({ confirmed: false });
  });

  it('confirm() with a typed reason closes with confirmed: true and trimmed reason', () => {
    component.reason = '  Ablehnung  ';
    component.confirm();
    expect(dialogRef.close).toHaveBeenCalledWith({ confirmed: true, reason: 'Ablehnung' });
  });
});
