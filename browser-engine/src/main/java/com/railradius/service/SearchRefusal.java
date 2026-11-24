package com.railradius.service;

/** Browser stand-in for the backend's SearchRefusal, whose parent is a Spring class: only what the search throws. */
public class SearchRefusal extends RuntimeException {

    private SearchRefusal(String message) {
        super(message);
    }

    static SearchRefusal tooSlow() {
        return new SearchRefusal("This search took too long and was stopped");
    }
}
