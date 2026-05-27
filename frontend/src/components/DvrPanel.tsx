import { useEffect, useMemo, useState } from 'react';
import { Rewind } from 'lucide-react';
import { api, type DvrEvent } from '../api';

const WINDOW_MS = 5 * 60_000;  // scrub-window length

export function DvrPanel() {
  const [offsetSec, setOffsetSec] = useState(0);   // 0 = now; positive = past
  const [events, setEvents] = useState<DvrEvent[]>([]);
  const [loading, setLoading] = useState(false);

  const { fromMs, toMs } = useMemo(() => {
    const to = Date.now() - offsetSec * 1000;
    return { fromMs: to - WINDOW_MS, toMs: to };
  }, [offsetSec]);

  useEffect(() => {
    setLoading(true);
    api.dvr(fromMs, toMs).then(setEvents).catch(() => {}).finally(() => setLoading(false));
  }, [fromMs, toMs]);

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-3">
        <Rewind className="text-ether-accent" size={18} />
        <span className="text-xs font-mono text-slate-400">
          Scrub: {offsetSec === 0
            ? 'live'
            : `${offsetSec}s ago (${new Date(toMs).toLocaleTimeString()})`}
        </span>
        <span className="text-xs font-mono text-slate-600 ml-auto">
          {loading ? 'loading…' : `${events.length} events`}
        </span>
      </div>
      <input
        type="range" min={0} max={3600} step={5}
        value={offsetSec}
        onChange={(e) => setOffsetSec(Number(e.target.value))}
        className="w-full accent-ether-accent"
      />
      <div className="max-h-72 overflow-y-auto font-mono text-[11px] leading-snug bg-ether-bg/40 rounded border border-ether-line">
        {events.length === 0 && (
          <div className="text-slate-600 italic p-2">no events in this window</div>
        )}
        {events.map((e) => (
          <div key={e.ts + '-' + e.connectionId} className="px-2 py-1 hover:bg-ether-line/40">
            <span className="text-slate-600">{new Date(e.ts).toLocaleTimeString()}</span>{' '}
            <span className="text-ether-accent">{e.stage.padEnd(15)}</span>{' '}
            <span className="text-slate-300">{e.decision}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
