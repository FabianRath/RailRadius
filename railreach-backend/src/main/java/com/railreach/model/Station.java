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
}
