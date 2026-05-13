package io.aetheros.bandshifter;

/**
 * Coarse traffic categories driving QoS weight selection. Classification
 * is heuristic (port + SNI hints), never deep inspection.
 */
public enum TrafficClass {
    INTERACTIVE,   // SSH, terminal — latency-critical
    REALTIME,      // VoIP/RTP — jitter-critical
    WEB,           // HTTPS bulk — throughput
    STREAMING,     // long-lived video — high tolerance for buffering
    BULK,          // background / large transfers
    UNKNOWN
}
