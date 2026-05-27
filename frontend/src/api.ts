import type { LanesResponse, ProviderView } from './types';

async function j<T>(r: Response): Promise<T> {
  if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
  return r.json() as Promise<T>;
}

export interface ChaosState {
  enabled: boolean;
  laneKillProbability: number;
  injectedLatencyMs: number;
  dnsDropProbability: number;
}

export interface DistributionSnapshot {
  classes: { class: string; bytes: number; flows: number }[];
}

export interface DvrEvent {
  ts: number;
  connectionId: string;
  stage: string;
  decision: string;
  tags: string;
}

export interface PolicySource {
  version: number;
  source: string;
}

export const api = {
  lanes:     () => fetch('/api/lanes').then(j<LanesResponse>),
  setStrategy: (strategy: string) =>
    fetch('/api/lanes/strategy', {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ strategy }),
    }).then(j<{ strategy: string }>),
  providers: () => fetch('/api/sentinel/providers').then(j<ProviderView[]>),
  health:    () => fetch('/api/diagnostics/health').then(j<unknown>),

  chaos:    () => fetch('/api/chaos').then(j<ChaosState>),
  setChaos: (s: ChaosState) =>
    fetch('/api/chaos', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(s),
    }).then(j<ChaosState>),
  killLane:   (id: number) => fetch(`/api/chaos/lanes/${id}/kill`,   { method: 'POST' }).then(j<unknown>),
  reviveLane: (id: number) => fetch(`/api/chaos/lanes/${id}/revive`, { method: 'POST' }).then(j<unknown>),

  distribution: () => fetch('/api/bandshifter/distribution').then(j<DistributionSnapshot>),

  dvr: (fromMs: number, toMs: number) =>
    fetch(`/api/dvr/events?fromMs=${fromMs}&toMs=${toMs}`).then(j<DvrEvent[]>),

  policySource: () => fetch('/api/policy/dsl').then(j<PolicySource>),
  installPolicy: (source: string) =>
    fetch('/api/policy/dsl', {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ source }),
    }).then((r) => r.json() as Promise<{ ok: boolean; version?: number; error?: string }>),
};
