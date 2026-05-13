package io.aetheros.bandshifter;

/**
 * Heuristic classifier — port + SNI hints only. No DPI, no payload
 * inspection. Wrong-but-cheap is acceptable; QoS is best-effort.
 */
public final class TrafficClassifier {

    private TrafficClassifier() {}

    public static TrafficClass classify(int port, String sni) {
        if (port == 22 || port == 23) return TrafficClass.INTERACTIVE;
        if (port == 5060 || port == 5061 || (port >= 16384 && port <= 32767)) return TrafficClass.REALTIME;

        if (sni != null) {
            String s = sni.toLowerCase();
            if (s.endsWith(".googlevideo.com") || s.contains("netflix") || s.contains("twitch")) {
                return TrafficClass.STREAMING;
            }
            if (s.contains("backup") || s.contains("update") || s.contains("apt") || s.contains("yum")) {
                return TrafficClass.BULK;
            }
        }
        if (port == 443 || port == 80) return TrafficClass.WEB;
        return TrafficClass.UNKNOWN;
    }

    /** WFQ weights (higher = served first under contention). */
    public static int weightOf(TrafficClass c) {
        return switch (c) {
            case INTERACTIVE -> 100;
            case REALTIME    -> 90;
            case WEB         -> 50;
            case STREAMING   -> 30;
            case BULK        -> 10;
            case UNKNOWN     -> 20;
        };
    }
}
