import type { ForensicsEvent, Stage } from '../types';

const STAGE_COLOR: Record<Stage, string> = {
  SOCKS_HANDSHAKE: 'text-slate-400',
  DNS:             'text-ether-accent',
  TLS_PEEK:        'text-fuchsia-400',
  LANE_PICK:       'text-amber-300',
  RELAY:           'text-emerald-400',
  CLOSE:           'text-slate-500',
};

export function ForensicsConsole({ events }: { events: ForensicsEvent[] }) {
  return (
    <div className="font-mono text-[11px] leading-snug max-h-[420px] overflow-y-auto">
      {events.length === 0 && (
        <div className="text-slate-600 italic p-2">waiting for events…</div>
      )}
      {events.map((e, i) => (
        <div key={i} className="px-2 py-1 hover:bg-ether-line/40 rounded">
          <span className="text-slate-600">{e.ts.slice(11, 23)}</span>{' '}
          <span className={STAGE_COLOR[e.stage] ?? 'text-slate-300'}>
            {e.stage.padEnd(15)}
          </span>{' '}
          <span className="text-slate-300">{e.decision}</span>
        </div>
      ))}
    </div>
  );
}
