package com.railradius.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The client-safe "no" to a search (GLOSSARY.md → Refusal). Its reason sets the HTTP status; its {@link #getReason()}
 * is the message for the user, which the stream sends as its error event ({@link #getMessage()} has the status in
 * front of it).
 */
public class SearchRefusal extends ResponseStatusException {

    public enum Reason {
        INVALID(HttpStatus.BAD_REQUEST),
        TOO_MANY_SEARCHES(HttpStatus.TOO_MANY_REQUESTS),
        BUSY(HttpStatus.SERVICE_UNAVAILABLE),
        TOO_SLOW(HttpStatus.SERVICE_UNAVAILABLE);

        private final HttpStatus status;

        Reason(HttpStatus status) {
            this.status = status;
        }
    }

    private final Reason refusalReason;

    private SearchRefusal(Reason refusalReason, String message) {
        super(refusalReason.status, message);
        this.refusalReason = refusalReason;
    }

    /** A request outside the API's bounds; the message says which. */
    static SearchRefusal invalid(String message) {
        return new SearchRefusal(Reason.INVALID, message);
    }

    /** The client's search budget is too low for the search. */
    static SearchRefusal tooManySearches(long waitSeconds) {
        return new SearchRefusal(Reason.TOO_MANY_SEARCHES,
                "Too many searches, please try again in " + waitSeconds + " s");
    }

    /** Every permit is taken and the queue is full. */
    static SearchRefusal busy() {
        return new SearchRefusal(Reason.BUSY, "Too many searches at once, please try again in a minute");
    }

    /** The search ran past its deadline. */
    static SearchRefusal tooSlow() {
        return new SearchRefusal(Reason.TOO_SLOW,
                "This search took too long and was stopped, please try fewer changes or a shorter wait");
    }

    public Reason refusalReason() {
        return refusalReason;
    }
}
