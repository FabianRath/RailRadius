package com.railradius.service;

/** Prototype stand-in (#8) for the backend's SearchRefusal, which extends Spring's ResponseStatusException. */
public class SearchRefusal extends RuntimeException {

    private SearchRefusal(String message) {
        super(message);
    }

    static SearchRefusal tooSlow() {
        return new SearchRefusal("This search took too long and was stopped");
    }
}
