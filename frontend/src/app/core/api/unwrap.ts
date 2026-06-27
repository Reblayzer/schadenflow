import { HttpErrorResponse } from '@angular/common/http';
import { Observable, catchError, map, throwError } from 'rxjs';
import { ApiResponse } from './api-response.model';
import { ApiClientError } from './api-error';

export function unwrap<T>() {
  return (source: Observable<ApiResponse<T>>): Observable<T> =>
    source.pipe(
      map((res) => {
        if (!res.ok || res.data === undefined) {
          throw new ApiClientError(
            res.error?.code ?? 'UNKNOWN',
            res.error?.message ?? 'Unbekannter Fehler',
          );
        }
        return res.data;
      }),
      catchError((err) => throwError(() => toApiError(err))),
    );
}

export function toApiError(err: unknown): ApiClientError {
  if (err instanceof ApiClientError) {
    return err;
  }
  if (err instanceof HttpErrorResponse) {
    const body = err.error as ApiResponse<unknown> | null;
    if (body && body.error) {
      return new ApiClientError(body.error.code, body.error.message);
    }
    if (err.status === 0) {
      return new ApiClientError('NETWORK', 'Netzwerkfehler — Server nicht erreichbar');
    }
    return new ApiClientError(String(err.status), err.message);
  }
  return new ApiClientError('UNKNOWN', 'Unbekannter Fehler');
}
