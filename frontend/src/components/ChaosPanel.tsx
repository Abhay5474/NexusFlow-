import { useEffect, useState } from 'react';
import { Flame, Skull, HeartPulse } from 'lucide-react';
import { api, type ChaosState } from '../api';
import type { LanesResponse } from '../types';

export function ChaosPanel({ lanes }: { lanes: LanesResponse | null }) {
  const [state, setState] = useState<ChaosState>({
    enabled: false, laneKillProbability: 0, injectedLatencyMs: 0, dnsDropProbability: 0,
  });

  useEffect(() => { api.chaos().then(setState).catch(() => {}); }, []);

  const update = (patch: Partial<ChaosState>) => {
    const next = { ...state, ...patch };
    setState(next);
    api.setChaos(next).catch(() => {});
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between p-3 rounded bg-ether-bg/60 border border-ether-line">
        <div className="flex items-center gap-3">
          <Flame className={state.enabled ? 'text-ether-err' : 'text-slate-500'} size={20} />
          <div>
            <div className="font-mono text-sm">Chaos Mode</div>
            <div className="text-xs text-slate-500">Inject faults into the data plane</div>
          </div>
        </div>
        <button
          onClick={() => update({ enabled: !state.enabled })}
          className={`px-4 py-2 rounded font-mono text-xs uppercase tracking-wider ${
            state.enabled
              ? 'bg-ether-err/20 text-ether-err border border-ether-err'
              : 'bg-ether-line text-slate-300 border border-ether-line'
          }`}
        >
          {state.enabled ? 'ARMED' : 'DISABLED'}
        </button>
      </div>

      <Slider label="Lane Kill Probability" suffix="%"
        value={Math.round(state.laneKillProbability * 100)} max={100}
        onChange={(v) => update({ laneKillProbability: v / 100 })} />

      <Slider label="Injected Latency" suffix="ms"
        value={state.injectedLatencyMs} max={1000}
        onChange={(v) => update({ injectedLatencyMs: v })} />

      <Slider label="DNS Drop Probability" suffix="%"
        value={Math.round(state.dnsDropProbability * 100)} max={100}
        onChange={(v) => update({ dnsDropProbability: v / 100 })} />

      <div>
        <div className="text-xs font-mono uppercase text-slate-500 mb-2">Manual Lane Control</div>
        <div className="flex gap-2 flex-wrap">
          {lanes?.lanes.map((l) => (
            <div key={l.id} className="flex items-center gap-1 border border-ether-line rounded px-2 py-1">
              <span className="text-xs font-mono text-slate-300">{l.label}</span>
              <button onClick={() => api.killLane(l.id)}   title="kill"  className="text-ether-err"><Skull size={14} /></button>
              <button onClick={() => api.reviveLane(l.id)} title="revive" className="text-ether-ok"><HeartPulse size={14} /></button>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function Slider({
  label, suffix, value, max, onChange,
}: { label: string; suffix: string; value: number; max: number; onChange: (v: number) => void }) {
  return (
    <div>
      <div className="flex justify-between text-xs font-mono text-slate-400 mb-1">
        <span>{label}</span>
        <span className="text-ether-accent">{value}{suffix}</span>
      </div>
      <input
        type="range" min={0} max={max} value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="w-full accent-ether-accent"
      />
    </div>
  );
}
