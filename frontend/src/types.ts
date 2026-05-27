export type Stage =
  | 'SOCKS_HANDSHAKE'
  | 'DNS'
  | 'TLS_PEEK'
  | 'LANE_PICK'
  | 'RELAY'
  | 'CLOSE';

export interface ForensicsEvent {
  ts: string;
  connectionId: string;
  stage: Stage;
  decision: string;
  tags: Record<string, unknown>;
}

export interface LaneView {
  id: number;
  label: string;
  latencyMs: number;
  errorRate: number;
  healthy: boolean;
  score: number;
}

export interface LanesResponse {
  strategy: string;
  lanes: LaneView[];
}

export interface ProviderView {
  id: string;
  endpoint: string;
  score: number;
}

export interface GeoArc {
  id: string;
  ts: number;
  lat: number;
  lon: number;
  country: string;
  city: string;
  domain: string;
}
