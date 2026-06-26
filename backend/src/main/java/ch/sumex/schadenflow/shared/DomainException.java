package ch.sumex.schadenflow.shared;

public abstract class DomainException extends RuntimeException {
    protected DomainException(String message) {
        super(message);
    }

    public static class NotFoundError extends DomainException {
        public NotFoundError(String message) { super(message); }
    }

    public static class ValidationError extends DomainException {
        public ValidationError(String message) { super(message); }
    }

    public static class ForbiddenError extends DomainException {
        public ForbiddenError(String message) { super(message); }
    }

    public static class IllegalTransitionError extends DomainException {
        public IllegalTransitionError(String message) { super(message); }
    }
}
