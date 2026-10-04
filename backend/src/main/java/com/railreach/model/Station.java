package com.railreach.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Station {
    private String id;
    private String name;
    private Double latitude;
    private Double longitude;
    private String parentStation;
    // ISO 3166-1 alpha-2 code from the coordinates; null at sea or outside the covered countries
    private String country;
}
