package io.aetheros.geo;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import io.aetheros.core.geo.GeoLookupPort;
import io.aetheros.core.geo.GeoPoint;

import java.io.File;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * MaxMind GeoLite2-City lookup. If the configured MMDB file is absent, the
 * lookup is a graceful no-op — the platform still runs without the DB, the
 * frontend globe just lights up with the "??" country marker.
 */
public final class MaxMindGeoLookup implements GeoLookupPort, AutoCloseable {

    private final DatabaseReader reader;

    public MaxMindGeoLookup(Path mmdbPath) {
        DatabaseReader r = null;
        try {
            if (mmdbPath != null && Files.isReadable(mmdbPath)) {
                r = new DatabaseReader.Builder(mmdbPath.toFile()).withCache(
                        new com.maxmind.db.CHMCache()).build();
            }
        } catch (Exception e) {
            r = null;
        }
        this.reader = r;
    }

    public boolean isReady() { return reader != null; }

    @Override
    public Optional<GeoPoint> lookup(InetAddress address) {
        if (reader == null || address == null) return Optional.empty();
        try {
            CityResponse resp = reader.tryCity(address).orElse(null);
            if (resp == null || resp.getLocation() == null
                    || resp.getLocation().getLatitude() == null) return Optional.empty();
            return Optional.of(new GeoPoint(
                    resp.getLocation().getLatitude(),
                    resp.getLocation().getLongitude(),
                    resp.getCountry() != null && resp.getCountry().getIsoCode() != null
                            ? resp.getCountry().getIsoCode() : "??",
                    resp.getCity() != null && resp.getCity().getName() != null
                            ? resp.getCity().getName() : ""));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
    }

    /** Convenience: locate MMDB by env var, system property, or default path. */
    public static Path resolveDefaultPath() {
        String env = System.getenv("AETHEROS_GEOLITE2");
        if (env != null && !env.isBlank()) return Path.of(env);
        String prop = System.getProperty("aetheros.geolite2");
        if (prop != null && !prop.isBlank()) return Path.of(prop);
        File local = new File("data/GeoLite2-City.mmdb");
        return local.toPath();
    }
}
