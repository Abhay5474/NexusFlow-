export type Stage =
  | 'SOCKS_HANDSHAKE'
  | 'DNS'
  | 'TLS_PEEK'
  | 'LANE_PICK'
  | 'RELAY'
  | 'CLOSE'
  | 'IDS_ALERT'
  | 'AEGIS_BLOCK'
  | 'BEACON_ALERT'
  | 'EXFIL_BLOCK'
  | 'QUIC_DROP'
  | 'SHARD'
  | 'BLACKHOLE';

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

export interface IdsAlert {
  id: string;
  ts: string;
  ruleId: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  payload: string;
  action: 'LOGGED' | 'DROPPED';
  srcIp: string;
  dstPort: number;
}

export interface AegisStats {
  totalBlocked: number;
  totalAllowed: number;
  blocklistSize: number;
  topBlockedDomains: { domain: string; count: number }[];
  recentBlocks: { domain: string; ts: string; category: string }[];
}

export interface IroncladStatus {
  adapterName: string;
  active: boolean;
  packetsIngested: number;
  bytesIngested: number;
  routingTableModified: boolean;
  virtualThreadPoolSize: number;
}

export interface BeaconAlert {
  connectionId: string;
  remoteHost: string;
  intervalMs: number;
  score: number;
  action: 'FLAGGED' | 'DISCONNECTED';
}

export interface CounterScoreEntry {
  pid: number;
  processName: string;
  bytesIn: number;
  bytesOut: number;
  blockedRequests: number;
  activeConnections: number;
  threatScore: number;
  protocols: string[];
}

export interface ShieldFabricAlert {
  ts: string;
  laneId: number;
  rttDeltaMs: number;
  pmtuChange: number;
  duplicateRatio: number;
  action: 'CIPHER_ROTATE' | 'INTERFACE_MIGRATE' | 'ALERT_ONLY';
}
