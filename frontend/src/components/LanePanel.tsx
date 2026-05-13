import type { LanesResponse } from '../types';

const STRATEGIES = ['round-robin', 'least-latency', 'weighted', 'congestion-aware'] as const;

export function LanePanel({
  data, onStrategy,
}: { data: LanesResponse | null; onStrategy: (s: string) => void }) {
  if (!data) return <div className="text-slate-600 text-sm italic">loading lanes…</div>;
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2 text-xs font-mono">
        <span className="text-slate-500">strategy:</span>
        <select
          value={data.strategy}
          onChange={(e) => onStrategy(e.target.value)}
          className="bg-ether-bg border border-ether-line rounded px-2 py-1 text-ether-accent"
        >
          {STRATEGIES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
      </div>
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        {data.lanes.map((l) => (
          <div
            key={l.id}
            className={`rounded border p-3 ${
              l.healthy ? 'border-ether-line' : 'border-ether-err/60'
            }`}
          >
            <div className="flex items-center justify-between text-xs font-mono">
              <span className="text-slate-400">{l.label}</span>
              <span className={l.healthy ? 'text-ether-ok' : 'text-ether-err'}>
                {l.healthy ? 'OK' : 'DOWN'}
              </span>
            </div>
            <div className="mt-2 text-2xl font-mono text-ether-accent">
              {l.latencyMs}<span className="text-xs text-slate-500"> ms</span>
            </div>
            <div className="mt-1 text-[11px] text-slate-500 font-mono">
              err {(l.errorRate * 100).toFixed(1)}% · score {l.score.toFixed(2)}
            </div>
            <div className="mt-2 h-1.5 bg-ether-line rounded overflow-hidden">
              <div
                className="h-full bg-ether-accent glow-line"
                style={{ width: `${Math.max(0, Math.min(1, l.score)) * 100}%` }}
              />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
