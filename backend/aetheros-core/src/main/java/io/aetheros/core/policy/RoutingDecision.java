package io.aetheros.core.policy;

public sealed interface RoutingDecision {
    record Allow()                       implements RoutingDecision {}
    record Deny(String reason)           implements RoutingDecision {}
    record PinLane(int laneId)           implements RoutingDecision {}

    RoutingDecision ALLOW = new Allow();
}
