package com.railradius.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A load from the zips was asked for while another runs (#20); it is turned away without building. */
public class GtfsLoadRunning extends ResponseStatusException {

    GtfsLoadRunning() {
        super(HttpStatus.CONFLICT, "A GTFS reload is already running");
    }
}
