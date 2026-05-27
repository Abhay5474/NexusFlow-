import { useEffect, useState } from 'react';
import { Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';
import { api, type DistributionSnapshot } from '../api';

const COLORS: Record<string, string> = {
  INTERACTIVE: '#56d4ff',
  REALTIME:    '#a78bfa',
  WEB:         '#4be3a3',
  STREAMING:   '#ffb347',
  BULK:        '#94a3b8',
  UNKNOWN:     '#475569',
};

export function TrafficPie() {
  const [snap, setSnap] = useState<DistributionSnapshot | null>(null);

  useEffect(() => {
    const tick = () => api.distribution().then(setSnap).catch(() => {});
    tick();
    const t = setInterval(tick, 2000);
    return () => clearInterval(t);
  }, []);

  if (!snap) return <div className="text-slate-600 text-sm italic">collecting samples…</div>;
  const data = snap.classes
    .filter((c) => c.bytes > 0)
    .map((c) => ({ name: c.class, value: c.bytes }));
  if (data.length === 0)
    return <div className="text-slate-600 text-sm italic">no traffic yet — send some bytes through :1080</div>;

  return (
    <div className="h-56">
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie data={data} dataKey="value" nameKey="name" innerRadius={40} outerRadius={80} paddingAngle={2}>
            {data.map((d) => <Cell key={d.name} fill={COLORS[d.name] ?? '#475569'} />)}
          </Pie>
          <Tooltip
            contentStyle={{ background: '#0d1322', border: '1px solid #1a2540', fontSize: 12, fontFamily: 'ui-monospace' }}
            formatter={(v: number) => `${(v / 1024).toFixed(1)} KiB`}
          />
          <Legend wrapperStyle={{ fontSize: 11, fontFamily: 'ui-monospace' }} />
        </PieChart>
      </ResponsiveContainer>
    </div>
  );
}
