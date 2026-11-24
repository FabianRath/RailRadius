package com.railradius.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown for a request outside the API's bounds; the message is safe to show to the client. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidSearchException extends IllegalArgumentException {

    InvalidSearchException(String message) {
        super(message);
    }
}
