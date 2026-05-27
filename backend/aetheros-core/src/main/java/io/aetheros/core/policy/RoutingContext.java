package io.aetheros.core.policy;

import java.time.LocalTime;

/** Decisional inputs available at the SOCKS REQUEST phase. */
public record RoutingContext(String domain, int port, LocalTime nowLocal) {}
