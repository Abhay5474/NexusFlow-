import { useEffect, useState } from 'react';
import { Area, AreaChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { ForensicsEvent } from '../types';

interface Sample { t: string; bytes: number; }
const WINDOW = 60;

export function ThroughputChart({ events }: { events: ForensicsEvent[] }) {
  const [samples, setSamples] = useState<Sample[]>([]);

  useEffect(() => {
    const id = setInterval(() => {
      const cutoff = Date.now() - 1000;
      const recentBytes = events
        .filter((e) => e.stage === 'CLOSE' && Date.parse(e.ts) >= cutoff)
        .reduce((acc, e) => acc + Number(e.tags?.bytes ?? 0), 0);
      const t = new Date().toLocaleTimeString();
      setSamples((cur) => [...cur.slice(-WINDOW + 1), { t, bytes: recentBytes }]);
    }, 1000);
    return () => clearInterval(id);
  }, [events]);

  return (
    <div className="h-44">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={samples}>
          <defs>
            <linearGradient id="g" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%"   stopColor="#56d4ff" stopOpacity={0.6} />
              <stop offset="100%" stopColor="#56d4ff" stopOpacity={0}   />
            </linearGradient>
          </defs>
          <XAxis dataKey="t" stroke="#445" tick={{ fontSize: 10 }} />
          <YAxis stroke="#445" tick={{ fontSize: 10 }} />
          <Tooltip
            contentStyle={{
              background: '#0d1322', border: '1px solid #1a2540',
              fontSize: 12, fontFamily: 'ui-monospace',
            }}
          />
          <Area type="monotone" dataKey="bytes" stroke="#56d4ff" fill="url(#g)" />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
