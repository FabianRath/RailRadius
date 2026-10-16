package com.railradius.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which country a coordinate lies in. Borders come from Natural Earth's 1:10m admin-0 countries, cut to Europe
 * ({@code borders/europe.geojson}, see docs/decisions.md): a GeoJSON FeatureCollection of MultiPolygons with an
 * {@code iso} property (ISO 3166-1 alpha-2).
 */
@Component
public class CountryLocator {

    private static final String BORDERS_RESOURCE = "borders/europe.geojson";

    private final List<CountryPolygon> polygons;

    public CountryLocator() {
        this(CountryLocator.class.getClassLoader().getResourceAsStream(BORDERS_RESOURCE));
    }

    CountryLocator(InputStream geoJson) {
        if (geoJson == null) throw new IllegalStateException("Missing border file " + BORDERS_RESOURCE);
        try (geoJson) {
            polygons = parse(new ObjectMapper().readTree(geoJson));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The ISO code of the country the point lies in; empty at sea or outside the covered countries. */
    Optional<String> countryAt(double lat, double lon) {
        return polygons.stream().filter(polygon -> polygon.contains(lat, lon)).map(CountryPolygon::iso).findFirst();
    }

    private static List<CountryPolygon> parse(JsonNode featureCollection) {
        List<CountryPolygon> result = new ArrayList<>();
        for (JsonNode feature : featureCollection.get("features")) {
            String iso = feature.get("properties").get("iso").asString();
            for (JsonNode polygon : feature.get("geometry").get("coordinates")) {
                List<double[][]> rings = new ArrayList<>();
                for (JsonNode ring : polygon) {
                    rings.add(parseRing(ring));
                }
                result.add(CountryPolygon.of(iso, rings));
            }
        }
        return result;
    }

    /** [lons, lats] of a GeoJSON ring of [lon, lat] points. */
    private static double[][] parseRing(JsonNode ring) {
        double[] lons = new double[ring.size()];
        double[] lats = new double[ring.size()];
        for (int i = 0; i < ring.size(); i++) {
            lons[i] = ring.get(i).get(0).asDouble();
            lats[i] = ring.get(i).get(1).asDouble();
        }
        return new double[][] {lons, lats};
    }

    /** One polygon of a country: its outer ring and holes, with the outer ring's bounding box as a cheap pre-check. */
    private record CountryPolygon(String iso, List<double[][]> rings,
                                  double minLat, double maxLat, double minLon, double maxLon) {

        static CountryPolygon of(String iso, List<double[][]> rings) {
            double[] lons = rings.getFirst()[0];
            double[] lats = rings.getFirst()[1];
            double minLat = Double.MAX_VALUE;
            double maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE;
            double maxLon = -Double.MAX_VALUE;
            for (int i = 0; i < lons.length; i++) {
                minLat = Math.min(minLat, lats[i]);
                maxLat = Math.max(maxLat, lats[i]);
                minLon = Math.min(minLon, lons[i]);
                maxLon = Math.max(maxLon, lons[i]);
            }
            return new CountryPolygon(iso, rings, minLat, maxLat, minLon, maxLon);
        }

        /** Even-odd ray casting over all rings, so a point in a hole counts as outside. */
        boolean contains(double lat, double lon) {
            if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return false;
            boolean inside = false;
            for (double[][] ring : rings) {
                double[] lons = ring[0];
                double[] lats = ring[1];
                for (int i = 0, j = lons.length - 1; i < lons.length; j = i++) {
                    boolean crosses = (lats[i] > lat) != (lats[j] > lat)
                            && lon < (lons[j] - lons[i]) * (lat - lats[i]) / (lats[j] - lats[i]) + lons[i];
                    if (crosses) inside = !inside;
                }
            }
            return inside;
        }
    }
}
