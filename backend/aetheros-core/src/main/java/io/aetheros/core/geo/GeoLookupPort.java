package io.aetheros.core.geo;

import java.net.InetAddress;
import java.util.Optional;

/**
 * Geo enrichment port. Implementations may back onto MaxMind GeoLite2 MMDB,
 * a no-op stub, or anything else. The data plane calls this synchronously
 * during DNS completion — implementations MUST be in-memory and lock-free.
 */
public interface GeoLookupPort {
    Optional<GeoPoint> lookup(InetAddress address);
}
