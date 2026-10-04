package com.railreach.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CountryLocatorTest {

    static CountryLocator countryLocator;

    @BeforeAll
    static void loadBorders() {
        countryLocator = new CountryLocator();
    }

    @Test
    void stationsNearABorderLieInTheirOwnCountry() {
        // Belgian feed's "Aix-la-Chapelle Ouest" and Liège-Guillemins, ~35 km apart across the border
        assertThat(countryLocator.countryAt(50.78078, 6.07055)).contains("DE");
        assertThat(countryLocator.countryAt(50.624551, 5.566698)).contains("BE");
    }

    @Test
    void franceAndNorwayHaveTheirIsoCodes() {
        // Natural Earth's ISO_A2 is "-99" for both; the border file uses ISO_A2_EH
        assertThat(countryLocator.countryAt(48.8443, 2.3744)).contains("FR");
        assertThat(countryLocator.countryAt(59.9111, 10.7528)).contains("NO");
    }

    @Test
    void theSeaIsInNoCountry() {
        assertThat(countryLocator.countryAt(55.0, 3.0)).isEmpty();
    }
}
