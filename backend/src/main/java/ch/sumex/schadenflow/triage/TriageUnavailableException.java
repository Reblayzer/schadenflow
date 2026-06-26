package ch.sumex.schadenflow.triage;

public class TriageUnavailableException extends RuntimeException {

    public TriageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
