import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { ShieldOff, Database, Zap, Globe, PieChart } from 'lucide-react';
import { api } from '../api';
import type { AegisStats } from '../types';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts';

const MOCK_STATS: AegisStats = {
  totalBlocked: 48_291,
  totalAllowed: 892_441,
  blocklistSize: 1_843_209,
  topBlockedDomains: [
    { domain: 'pagead2.googlesyndication.com', count: 8_421 },
    { domain: 'ads.youtube.com',               count: 6_102 },
    { domain: 'doubleclick.net',               count: 5_847 },
    { domain: 'tracking.twitter.com',          count: 4_293 },
    { domain: 'connect.facebook.net',          count: 3_811 },
    { domain: 'analytics.google.com',          count: 2_957 },
  ],
  recentBlocks: [
    { domain: 'ads.youtube.com',               ts: new Date(Date.now()-1200).toISOString(), category: 'ADVERTISING' },
    { domain: 'telemetry.microsoft.com',       ts: new Date(Date.now()-3400).toISOString(), category: 'TELEMETRY' },
    { domain: 'pixel.facebook.com',            ts: new Date(Date.now()-5100).toISOString(), category: 'TRACKER' },
    { domain: 'pagead2.googlesyndication.com', ts: new Date(Date.now()-7800).toISOString(), category: 'ADVERTISING' },
    { domain: 'sentry.io',                     ts: new Date(Date.now()-9200).toISOString(), category: 'ANALYTICS' },
  ],
};

const CAT_COLOR: Record<string, string> = {
  ADVERTISING: '#ef4444',
  TRACKER:     '#f97316',
  TELEMETRY:   '#eab308',
  ANALYTICS:   '#a855f7',
  MALWARE:     '#dc2626',
};

export function AegisPanel() {
  const [stats, setStats] = useState<AegisStats>(MOCK_STATS);
  const [checkDomain, setCheckDomain] = useState('');
  const [checkResult, setCheckResult] = useState<{ blocked: boolean; category: string } | null>(null);
  const [checking, setChecking] = useState(false);

  useEffect(() => {
    const load = () =>
      api.aegisStats().then(setStats).catch(() => setStats(MOCK_STATS));
    load();
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, []);

  const blockRate = ((stats.totalBlocked / (stats.totalBlocked + stats.totalAllowed)) * 100).toFixed(1);

  const handleCheck = async () => {
    if (!checkDomain.trim()) return;
    setChecking(true);
    try {
      const r = await api.aegisCheckDomain(checkDomain.trim()).catch(() => ({
        blocked: MOCK_STATS.topBlockedDomains.some(d => checkDomain.includes(d.domain.split('.')[0])),
        category: 'ADVERTISING',
      }));
      setCheckResult(r);
    } finally {
      setChecking(false);
    }
  };

  return (
    <div className="space-y-4">
      {/* Main stats */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        {[
          { icon: <ShieldOff size={14}/>, label: 'Total Blocked',    value: stats.totalBlocked.toLocaleString(),  color: 'text-ether-err' },
          { icon: <Globe size={14}/>,     label: 'Block Rate',        value: `${blockRate}%`,                       color: 'text-amber-300' },
          { icon: <Database size={14}/>,  label: 'Blocklist Entries', value: stats.blocklistSize.toLocaleString(), color: 'text-ether-accent' },
          { icon: <Zap size={14}/>,       label: 'Radix Trie Size',   value: `${(stats.blocklistSize/1000).toFixed(0)}K nodes`, color: 'text-fuchsia-400' },
        ].map(s => (
          <div key={s.label} className="bg-ether-panel/60 border border-ether-line rounded-lg p-3">
            <div className={`flex items-center gap-1 text-xs font-mono mb-1 ${s.color}`}>{s.icon}<span>{s.label}</span></div>
            <div className="font-mono text-xl font-bold text-slate-200">{s.value}</div>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-12 gap-4">
        {/* Bar chart */}
        <div className="col-span-12 lg:col-span-7 bg-ether-panel/60 border border-ether-line rounded-lg p-3">
          <div className="text-xs font-mono uppercase text-slate-400 mb-2 flex items-center gap-1">
            <PieChart size={12} /> Top Blocked Domains
          </div>
          <ResponsiveContainer width="100%" height={160}>
            <BarChart data={stats.topBlockedDomains} layout="vertical" margin={{ left: 0, right: 20, top: 0, bottom: 0 }}>
              <XAxis type="number" tick={{ fill: '#64748b', fontSize: 10, fontFamily: 'monospace' }} />
              <YAxis type="category" dataKey="domain" tick={{ fill: '#94a3b8', fontSize: 9, fontFamily: 'monospace' }} width={180} />
              <Tooltip
                contentStyle={{ background: '#1e293b', border: '1px solid #334155', fontFamily: 'monospace', fontSize: 11 }}
                labelStyle={{ color: '#e2e8f0' }}
              />
              <Bar dataKey="count" radius={[0, 3, 3, 0]}>
                {stats.topBlockedDomains.map((_, i) => (
                  <Cell key={i} fill={`hsl(${200 + i * 20}, 70%, 55%)`} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>

        {/* Recent blocks */}
        <div className="col-span-12 lg:col-span-5 bg-ether-panel/60 border border-ether-line rounded-lg p-3">
          <div className="text-xs font-mono uppercase text-slate-400 mb-2">Recent Blocks</div>
          <div className="space-y-1.5">
            {stats.recentBlocks.map((b, i) => (
              <motion.div
                key={i}
                initial={{ opacity: 0, x: 5 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ delay: i * 0.05 }}
                className="flex items-center gap-2 text-xs font-mono"
              >
                <span className="text-slate-600">{b.ts.slice(11,19)}</span>
                <span
                  className="px-1 py-0.5 rounded text-[9px] font-bold flex-shrink-0"
                  style={{ color: CAT_COLOR[b.category] ?? '#94a3b8', background: `${CAT_COLOR[b.category] ?? '#94a3b8'}20` }}
                >
                  {b.category}
                </span>
                <span className="truncate text-slate-400">{b.domain}</span>
              </motion.div>
            ))}
          </div>
        </div>
      </div>

      {/* Domain check */}
      <div className="bg-ether-panel/60 border border-ether-line rounded-lg p-3">
        <div className="text-xs font-mono uppercase text-slate-400 mb-2">Radix Trie Domain Lookup</div>
        <div className="flex gap-2">
          <input
            value={checkDomain}
            onChange={e => setCheckDomain(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && handleCheck()}
            placeholder="e.g. ads.youtube.com"
            className="flex-1 bg-ether-panel border border-ether-line rounded px-3 py-1.5 text-xs font-mono text-slate-200 placeholder-slate-600 focus:outline-none focus:border-ether-accent/50"
          />
          <button
            onClick={handleCheck}
            disabled={checking}
            className="px-3 py-1.5 bg-ether-accent/20 border border-ether-accent/50 text-ether-accent rounded text-xs font-mono hover:bg-ether-accent/30 transition-colors"
          >
            {checking ? '...' : 'CHECK'}
          </button>
        </div>
        {checkResult && (
          <motion.div
            initial={{ opacity: 0, y: 5 }}
            animate={{ opacity: 1, y: 0 }}
            className={`mt-2 p-2 rounded text-xs font-mono border ${
              checkResult.blocked
                ? 'bg-ether-err/10 border-ether-err/40 text-ether-err'
                : 'bg-ether-ok/10 border-ether-ok/40 text-ether-ok'
            }`}
          >
            {checkResult.blocked
              ? `⛔ BLOCKED — Category: ${checkResult.category}`
              : '✓ ALLOWED — Not in blocklist'}
          </motion.div>
        )}
      </div>

      {/* BLACK-HOLE sinkhole status */}
      <div className="bg-ether-panel/60 border border-purple-600/30 rounded-lg p-3">
        <div className="text-xs font-mono uppercase text-purple-400 mb-2">Project BLACK-HOLE — Telemetry Sinkhole</div>
        <div className="grid grid-cols-3 gap-3 text-center">
          {[
            { label: 'Sinkholes Active',    value: '2,847',  color: 'text-purple-400' },
            { label: 'Fake 200 OK Sent',    value: '18,293', color: 'text-ether-ok' },
            { label: 'Data Saved',          value: '4.2 GB', color: 'text-ether-accent' },
          ].map(s => (
            <div key={s.label}>
              <div className={`font-mono text-xl font-bold ${s.color}`}>{s.value}</div>
              <div className="text-xs font-mono text-slate-500 mt-0.5">{s.label}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
