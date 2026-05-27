package io.aetheros.control.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aetheros.core.policy.RoutingContext;
import io.aetheros.core.policy.RoutingDecision;
import io.aetheros.core.policy.RoutingPolicy;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * JSON DSL for zero-trust routing rules. Schema:
 *
 * <pre>{@code
 * {
 *   "rules": [
 *     { "if": { "domainEndsWith": ".ru" },                     "action": "deny", "reason": "geo-block" },
 *     { "if": { "domainEndsWith": ".internal" },               "action": "pin",  "lane": 0 },
 *     { "if": { "port": 25 },                                  "action": "deny", "reason": "smtp" },
 *     { "if": { "domainContains": "ads", "afterHour": 22 },    "action": "deny", "reason": "night-block" },
 *     { "if": { "any": true },                                 "action": "allow" }
 *   ]
 * }
 * }</pre>
 *
 * Rules are evaluated top-to-bottom; first match wins. An empty rule list
 * is equivalent to ALLOW_ALL.
 */
public final class PolicyDsl {

    private static final ObjectMapper M = new ObjectMapper();

    private PolicyDsl() {}

    public static RoutingPolicy compile(String json) throws Exception {
        JsonNode root = M.readTree(json);
        JsonNode rules = root.path("rules");
        if (!rules.isArray() || rules.isEmpty()) return RoutingPolicy.ALLOW_ALL;

        List<Rule> compiled = new ArrayList<>();
        for (JsonNode r : rules) compiled.add(compileRule(r));

        return ctx -> {
            for (Rule rule : compiled) {
                if (rule.matches.test(ctx)) return rule.decision;
            }
            return RoutingDecision.ALLOW;
        };
    }

    private static Rule compileRule(JsonNode r) {
        JsonNode cond = r.path("if");
        Predicate<RoutingContext> p = ctx -> true;
        if (cond.has("any") && cond.get("any").asBoolean(false)) {
            // matches everything
        } else {
            p = compileCondition(cond);
        }
        RoutingDecision decision = switch (r.path("action").asText("allow")) {
            case "deny" -> new RoutingDecision.Deny(r.path("reason").asText("denied"));
            case "pin"  -> new RoutingDecision.PinLane(r.path("lane").asInt(0));
            default     -> RoutingDecision.ALLOW;
        };
        return new Rule(p, decision);
    }

    private static Predicate<RoutingContext> compileCondition(JsonNode cond) {
        Predicate<RoutingContext> acc = ctx -> true;
        if (cond.hasNonNull("domainEndsWith")) {
            String s = cond.get("domainEndsWith").asText().toLowerCase();
            acc = and(acc, ctx -> ctx.domain() != null && ctx.domain().toLowerCase().endsWith(s));
        }
        if (cond.hasNonNull("domainContains")) {
            String s = cond.get("domainContains").asText().toLowerCase();
            acc = and(acc, ctx -> ctx.domain() != null && ctx.domain().toLowerCase().contains(s));
        }
        if (cond.hasNonNull("domainEquals")) {
            String s = cond.get("domainEquals").asText().toLowerCase();
            acc = and(acc, ctx -> ctx.domain() != null && ctx.domain().equalsIgnoreCase(s));
        }
        if (cond.hasNonNull("port")) {
            int p = cond.get("port").asInt();
            acc = and(acc, ctx -> ctx.port() == p);
        }
        if (cond.hasNonNull("portIn")) {
            List<Integer> ports = new ArrayList<>();
            cond.get("portIn").forEach(n -> ports.add(n.asInt()));
            acc = and(acc, ctx -> ports.contains(ctx.port()));
        }
        if (cond.hasNonNull("afterHour")) {
            int h = cond.get("afterHour").asInt();
            acc = and(acc, ctx -> ctx.nowLocal().isAfter(LocalTime.of(h, 0)));
        }
        if (cond.hasNonNull("beforeHour")) {
            int h = cond.get("beforeHour").asInt();
            acc = and(acc, ctx -> ctx.nowLocal().isBefore(LocalTime.of(h, 0)));
        }
        return acc;
    }

    private static Predicate<RoutingContext> and(Predicate<RoutingContext> a,
                                                 Predicate<RoutingContext> b) {
        return ctx -> a.test(ctx) && b.test(ctx);
    }

    private record Rule(Predicate<RoutingContext> matches, RoutingDecision decision) {}
}
