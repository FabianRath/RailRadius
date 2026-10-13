package com.railreach.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** The countries with a feed of their own (ISO codes, sorted); returned by {@code GET /api/coverage}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Coverage {

    private List<String> countries;
}
