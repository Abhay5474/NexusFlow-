package io.aetheros.core.policy;

/**
 * Hot-swappable policy port. The SOCKS request handler consults the
 * currently-installed policy synchronously on every CONNECT; updates from
 * the control plane atomically replace the reference (no restart).
 */
@FunctionalInterface
public interface RoutingPolicy {
    RoutingDecision evaluate(RoutingContext ctx);

    RoutingPolicy ALLOW_ALL = ctx -> RoutingDecision.ALLOW;
}
