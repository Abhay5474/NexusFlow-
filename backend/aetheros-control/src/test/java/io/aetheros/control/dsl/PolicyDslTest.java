package io.aetheros.control.dsl;

import io.aetheros.core.policy.RoutingContext;
import io.aetheros.core.policy.RoutingDecision;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyDslTest {

    @Test
    void firstMatchWins_andDenyShortCircuits() throws Exception {
        var p = PolicyDsl.compile("""
            { "rules": [
                { "if": { "port": 25 },                    "action": "deny",  "reason": "smtp" },
                { "if": { "domainEndsWith": ".internal" }, "action": "pin",   "lane": 2 },
                { "if": { "any": true },                   "action": "allow" }
            ]}""");

        assertThat(p.evaluate(new RoutingContext("mail.example.com", 25, LocalTime.NOON)))
                .isInstanceOf(RoutingDecision.Deny.class);
        assertThat(p.evaluate(new RoutingContext("svc.internal", 443, LocalTime.NOON)))
                .isEqualTo(new RoutingDecision.PinLane(2));
        assertThat(p.evaluate(new RoutingContext("example.com", 443, LocalTime.NOON)))
                .isInstanceOf(RoutingDecision.Allow.class);
    }

    @Test
    void afterHourGate() throws Exception {
        var p = PolicyDsl.compile("""
            { "rules": [
                { "if": { "domainContains": "ads", "afterHour": 22 }, "action": "deny" },
                { "if": { "any": true },                              "action": "allow" }
            ]}""");

        assertThat(p.evaluate(new RoutingContext("ads.example.com", 443, LocalTime.of(23, 0))))
                .isInstanceOf(RoutingDecision.Deny.class);
        assertThat(p.evaluate(new RoutingContext("ads.example.com", 443, LocalTime.of(12, 0))))
                .isInstanceOf(RoutingDecision.Allow.class);
    }

    @Test
    void emptyRulesAllowAll() throws Exception {
        var p = PolicyDsl.compile("{\"rules\": []}");
        assertThat(p.evaluate(new RoutingContext("x", 80, LocalTime.NOON)))
                .isInstanceOf(RoutingDecision.Allow.class);
    }
}
