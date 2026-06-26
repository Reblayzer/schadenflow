package ch.sumex.schadenflow.shared;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.NotFoundError.class)
    public ResponseEntity<ApiResponse<Object>> handleNotFound(DomainException.NotFoundError ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(DomainException.IllegalTransitionError.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalTransition(DomainException.IllegalTransitionError ex) {
        return build(HttpStatus.CONFLICT, "ILLEGAL_TRANSITION", ex.getMessage());
    }

    @ExceptionHandler(DomainException.ForbiddenError.class)
    public ResponseEntity<ApiResponse<Object>> handleForbidden(DomainException.ForbiddenError ex) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage());
    }

    @ExceptionHandler(DomainException.ValidationError.class)
    public ResponseEntity<ApiResponse<Object>> handleDomainValidation(DomainException.ValidationError ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", ex.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Object>> handleBadRequest(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUncaught(Exception ex) {
        // Log details server-side; return a generic message to the client.
        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class)
                .error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ResponseEntity<ApiResponse<Object>> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiResponse.error(code, message));
    }
}
