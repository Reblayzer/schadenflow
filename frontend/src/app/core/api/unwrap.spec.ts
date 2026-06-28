import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { ApiResponse } from './api-response.model';
import { ApiClientError } from './api-error';
import { unwrap, toApiError } from './unwrap';

describe('unwrap', () => {
  it('maps data out when ok', (done) => {
    of({ ok: true, data: 42 } as ApiResponse<number>)
      .pipe(unwrap<number>())
      .subscribe((v) => {
        expect(v).toBe(42);
        done();
      });
  });

  it('throws ApiClientError when ok is false', (done) => {
    of({ ok: false, error: { code: 'X', message: 'm' } } as ApiResponse<number>)
      .pipe(unwrap<number>())
      .subscribe({
        error: (e) => {
          expect(e).toBeInstanceOf(ApiClientError);
          expect((e as ApiClientError).code).toBe('X');
          done();
        },
      });
  });

  it('errors with ApiClientError when data is undefined', (done) => {
    of({ ok: true } as ApiResponse<number>)
      .pipe(unwrap<number>())
      .subscribe({
        error: (e) => {
          expect(e).toBeInstanceOf(ApiClientError);
          done();
        },
      });
  });

  it('converts an HttpErrorResponse body to ApiClientError', (done) => {
    const httpErr = new HttpErrorResponse({
      status: 401,
      error: { ok: false, error: { code: 'INVALID_CREDENTIALS', message: 'bad' } },
    });
    throwError(() => httpErr)
      .pipe(unwrap<number>())
      .subscribe({
        error: (e) => {
          expect((e as ApiClientError).code).toBe('INVALID_CREDENTIALS');
          done();
        },
      });
  });
});

describe('toApiError', () => {
  it('maps status 0 to NETWORK', () => {
    const e = toApiError(new HttpErrorResponse({ status: 0 }));
    expect(e.code).toBe('NETWORK');
  });
});
