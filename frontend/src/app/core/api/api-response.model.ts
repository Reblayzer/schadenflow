export interface ApiError {
  code: string;
  message: string;
}

export interface ApiResponse<T> {
  ok: boolean;
  data?: T;
  error?: ApiError;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}
