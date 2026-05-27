package io.aetheros.core.geo;

public record GeoPoint(double latitude, double longitude, String country, String city) {
    public static final GeoPoint UNKNOWN = new GeoPoint(0, 0, "??", "");
}
