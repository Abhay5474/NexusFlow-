import type {
  LanesResponse, ProviderView, IdsAlert, AegisStats,
  IroncladStatus, CounterScoreEntry, ShieldFabricAlert
} from './types';

const BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';
const WS   = import.meta.env.VITE_WS_BASE  ?? 'ws://localhost:8080';

async function get<T>(path: string): Promise<T> {
  const r = await fetch(`${BASE}${path}`);
  if (!r.ok) throw new Error(`GET ${path} → ${r.status}`);
  return r.json() as Promise<T>;
}

async function post<T>(path: string, body?: unknown): Promise<T> {
  const r = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!r.ok) throw new Error(`POST ${path} → ${r.status}`);
  return r.json() as Promise<T>;
}

async function put<T>(path: string, body?: unknown): Promise<T> {
  const r = await fetch(`${BASE}${path}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!r.ok) throw new Error(`PUT ${path} → ${r.status}`);
  return r.json() as Promise<T>;
}

export const api = {
  // Existing
  lanes:       ()            => get<LanesResponse>('/api/lanes'),
  providers:   ()            => get<ProviderView[]>('/api/sentinel/providers'),
  setStrategy: (s: string)   => put<LanesResponse>('/api/lanes/strategy', { strategy: s }),
  chaos:       ()            => get<unknown>('/api/chaos'),
  setChaos:    (b: unknown)  => post<unknown>('/api/chaos', b),
  killLane:    (id: number)  => post<void>(`/api/chaos/lanes/${id}/kill`),
  reviveLane:  (id: number)  => post<void>(`/api/chaos/lanes/${id}/revive`),
  distribution:()            => get<unknown>('/api/bandshifter/distribution'),
  dvrEvents:   (from: number, to: number) =>
    get<unknown[]>(`/api/dvr/events?fromMs=${from}&toMs=${to}`),
  policy:      ()            => get<unknown>('/api/policy/dsl'),
  setPolicy:   (src: string) => put<unknown>('/api/policy/dsl', { source: src }),

  // NEW: IRONCLAD
  ironcladStatus: () => get<IroncladStatus>('/api/ironclad/status'),
  ironcladStart:  () => post<IroncladStatus>('/api/ironclad/start'),
  ironcladStop:   () => post<IroncladStatus>('/api/ironclad/stop'),

  // NEW: AEGIS
  aegisStats:       ()                  => get<AegisStats>('/api/aegis/stats'),
  aegisAddBlocklist:(url: string)       => post<void>('/api/aegis/blocklist', { url }),
  aegisCheckDomain: (domain: string)    => get<{ blocked: boolean; category: string }>(`/api/aegis/check?domain=${domain}`),

  // NEW: IDS
  idsAlerts:    ()  => get<IdsAlert[]>('/api/ids/alerts'),
  idsRules:     ()  => get<unknown[]>('/api/ids/rules'),
  idsAddRule:   (rule: unknown) => post<void>('/api/ids/rules', rule),

  // NEW: COUNTER-SCORE
  counterScore: () => get<CounterScoreEntry[]>('/api/counterscore/flows'),

  // NEW: SHIELD-FABRIC
  shieldAlerts: () => get<ShieldFabricAlert[]>('/api/shield/alerts'),

  websocketUrl: () => `${WS}/ws/forensics`,
};
