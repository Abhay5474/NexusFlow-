import type { LanesResponse, ProviderView } from './types';

async function j<T>(r: Response): Promise<T> {
  if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
  return r.json() as Promise<T>;
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
};
