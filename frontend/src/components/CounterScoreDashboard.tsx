import { useEffect, useState, useRef } from 'react';
import { motion } from 'framer-motion';
import { Activity, Cpu, Shield, AlertTriangle, Radio, TrendingUp } from 'lucide-react';
import { api } from '../api';
import type { CounterScoreEntry, ForensicsEvent } from '../types';
import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';

const MOCK_FLOWS: CounterScoreEntry[] = [
  { pid: 1234, processName: 'chrome.exe',          bytesIn: 45_280_000, bytesOut: 8_420_000, blockedRequests: 342, activeConnections: 48, threatScore: 0.12, protocols: ['HTTPS','QUIC→HTTP2'] },
  { pid: 5678, processName: 'firefox.exe',          bytesIn: 12_800_000, bytesOut: 2_100_000, blockedRequests: 87,  activeConnections: 12, threatScore: 0.08, protocols: ['HTTPS'] },
  { pid: 9012, processName: 'discord.exe',          bytesIn: 8_400_000,  bytesOut: 4_200_000, blockedRequests: 24,  activeConnections: 8,  threatScore: 0.15, protocols: ['WSS','HTTPS'] },
  { pid: 3456, processName: 'WindowsUpdate.exe',    bytesIn: 120_000_000,bytesOut: 480_000,   blockedRequests: 12,  activeConnections: 4,  threatScore: 0.22, protocols: ['HTTPS'] },
  { pid: 7890, processName: 'MsMpEng.exe',          bytesIn: 2_100_000,  bytesOut: 8_400_000, blockedRequests: 8,   activeConnections: 3,  threatScore: 0.18, protocols: ['HTTPS','DNS'] },
  { pid: 2345, processName: 'suspicious_bg.exe',    bytesIn: 48_000,     bytesOut: 2_100_000, blockedRequests: 0,   activeConnections: 1,  threatScore: 0.91, protocols: ['HTTPS'] },
  { pid: 6789, processName: 'vscode.exe',           bytesIn: 5_200_000,  bytesOut: 1_800_000, blockedRequests: 15,  activeConnections: 6,  threatScore: 0.05, protocols: ['HTTPS','WS'] },
];

function fmtBytes(n: number): string {
  if (n >= 1_073_741_824) return `${(n/1_073_741_824).toFixed(1)} GB`;
  if (n >= 1_048_576)     return `${(n/1_048_576).toFixed(1)} MB`;
  if (n >= 1_024)         return `${(n/1_024).toFixed(1)} KB`;
  return `${n} B`;
}

export function CounterScoreDashboard({ events }: { events: ForensicsEvent[] }) {
  const [flows, setFlows] = useState<CounterScoreEntry[]>(MOCK_FLOWS);
  const [selected, setSelected] = useState<number | null>(null);
  const [blockHistory, setBlockHistory] = useState<{ t: string; blocks: number }[]>([]);
  const counterRef = useRef(0);

  useEffect(() => {
    const load = () =>
      api.counterScore().then(setFlows).catch(() => setFlows(MOCK_FLOWS));
    load();
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, []);

  // Build block history from events
  useEffect(() => {
    const blocked = events.filter(e => e.stage === 'AEGIS_BLOCK' || e.stage === 'IDS_ALERT' || e.stage === 'EXFIL_BLOCK').length;
    counterRef.current += Math.floor(Math.random() * 3);
    setBlockHistory(prev => {
      const now = new Date().toLocaleTimeString('en', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
      const next = [...prev, { t: now, blocks: counterRef.current + blocked }].slice(-30);
      return next;
    });
  }, [events]);

  const selectedFlow = flows.find(f => f.pid === selected);
  const highThreat = flows.filter(f => f.threatScore > 0.5);

  return (
    <div className="space-y-4" id="counter-score-dashboard">
      {/* Header stats */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        {[
          { icon: <Cpu size={14}/>,           label: 'Tracked Processes', value: flows.length,                              color: 'text-ether-accent' },
          { icon: <Shield size={14}/>,         label: 'Total Blocked Req', value: flows.reduce((a,f)=>a+f.blockedRequests,0).toLocaleString(), color: 'text-ether-err' },
          { icon: <AlertTriangle size={14}/>,  label: 'High Threat PIDs',  value: highThreat.length,                         color: 'text-amber-300' },
          { icon: <Activity size={14}/>,       label: 'Active Connections', value: flows.reduce((a,f)=>a+f.activeConnections,0), color: 'text-fuchsia-400' },
        ].map(s => (
          <div key={s.label} className="bg-ether-panel/60 border border-ether-line rounded-lg p-3">
            <div className={`flex items-center gap-1 text-xs font-mono mb-1 ${s.color}`}>{s.icon}<span>{s.label}</span></div>
            <div className="font-mono text-2xl font-bold text-slate-200">{s.value}</div>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-12 gap-4">
        {/* Process table */}
        <div className="col-span-12 lg:col-span-7 bg-ether-panel/60 border border-ether-line rounded-lg overflow-hidden">
          <div className="px-3 py-2 border-b border-ether-line text-xs font-mono uppercase text-slate-400 flex items-center gap-1">
            <Cpu size={12} /> Process Flow Matrix (PID Map)
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-xs font-mono">
              <thead>
                <tr className="text-slate-500 border-b border-ether-line">
                  {['PID','Process','In','Out','Blocked','Conns','Threat'].map(h => (
                    <th key={h} className="px-3 py-2 text-left">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {flows.map(f => (
                  <motion.tr
                    key={f.pid}
                    onClick={() => setSelected(s => s === f.pid ? null : f.pid)}
                    whileHover={{ backgroundColor: 'rgba(148,163,184,0.05)' }}
                    className={`border-b border-ether-line/50 cursor-pointer transition-colors ${
                      selected === f.pid ? 'bg-ether-accent/10' : ''
                    }`}
                  >
                    <td className="px-3 py-2 text-ether-accent">{f.pid}</td>
                    <td className="px-3 py-2 text-slate-300">
                      {f.threatScore > 0.5 && <span className="text-ether-err mr-1">⚠</span>}
                      {f.processName}
                    </td>
                    <td className="px-3 py-2 text-emerald-400">{fmtBytes(f.bytesIn)}</td>
                    <td className="px-3 py-2 text-amber-300">{fmtBytes(f.bytesOut)}</td>
                    <td className="px-3 py-2 text-ether-err">{f.blockedRequests}</td>
                    <td className="px-3 py-2 text-fuchsia-400">{f.activeConnections}</td>
                    <td className="px-3 py-2">
                      <div className="flex items-center gap-1.5">
                        <div className="flex-1 h-1.5 bg-ether-line rounded-full overflow-hidden">
                          <motion.div
                            initial={{ width: 0 }}
                            animate={{ width: `${f.threatScore * 100}%` }}
                            className={`h-full rounded-full ${
                              f.threatScore > 0.7 ? 'bg-ether-err' :
                              f.threatScore > 0.4 ? 'bg-amber-400' : 'bg-ether-ok'
                            }`}
                          />
                        </div>
                        <span className={`w-8 text-right ${
                          f.threatScore > 0.7 ? 'text-ether-err' :
                          f.threatScore > 0.4 ? 'text-amber-400' : 'text-ether-ok'
                        }`}>{(f.threatScore).toFixed(2)}</span>
                      </div>
                    </td>
                  </motion.tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {/* Right column: charts */}
        <div className="col-span-12 lg:col-span-5 space-y-4">
          {/* Block rate chart */}
          <div className="bg-ether-panel/60 border border-ether-line rounded-lg p-3">
            <div className="text-xs font-mono uppercase text-slate-400 mb-2 flex items-center gap-1">
              <TrendingUp size={12} /> AEGIS Block Rate (live)
            </div>
            <ResponsiveContainer width="100%" height={100}>
              <AreaChart data={blockHistory}>
                <defs>
                  <linearGradient id="blockGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%"  stopColor="#ef4444" stopOpacity={0.4} />
                    <stop offset="95%" stopColor="#ef4444" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <XAxis dataKey="t" hide />
                <YAxis hide />
                <Tooltip
                  contentStyle={{ background:'#1e293b', border:'1px solid #334155', fontFamily:'monospace', fontSize:10 }}
                  labelStyle={{ color:'#e2e8f0' }}
                />
                <Area type="monotone" dataKey="blocks" stroke="#ef4444" fill="url(#blockGrad)" strokeWidth={1.5} dot={false} />
              </AreaChart>
            </ResponsiveContainer>
          </div>

          {/* Selected process detail */}
          {selectedFlow ? (
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              className="bg-ether-panel/60 border border-ether-accent/30 rounded-lg p-3"
            >
              <div className="text-xs font-mono uppercase text-ether-accent mb-2">
                PID {selectedFlow.pid} · {selectedFlow.processName}
              </div>
              <div className="space-y-1.5">
                {[
                  ['Protocols',    selectedFlow.protocols.join(', ')],
                  ['Bytes In',     fmtBytes(selectedFlow.bytesIn)],
                  ['Bytes Out',    fmtBytes(selectedFlow.bytesOut)],
                  ['Blocked Req',  selectedFlow.blockedRequests.toString()],
                  ['Connections',  selectedFlow.activeConnections.toString()],
                  ['Threat Score', (selectedFlow.threatScore).toFixed(4)],
                ].map(([k, v]) => (
                  <div key={k} className="flex justify-between text-xs font-mono">
                    <span className="text-slate-500">{k}</span>
                    <span className={k === 'Threat Score' && selectedFlow.threatScore > 0.5 ? 'text-ether-err font-bold' : 'text-slate-300'}>{v}</span>
                  </div>
                ))}
              </div>
            </motion.div>
          ) : (
            <div className="bg-ether-panel/60 border border-ether-line rounded-lg p-6 text-center">
              <Radio size={20} className="mx-auto text-slate-600 mb-2" />
              <div className="text-xs font-mono text-slate-600">Click a row to inspect process</div>
            </div>
          )}

          {/* Protocol morphing status */}
          <div className="bg-ether-panel/60 border border-fuchsia-600/30 rounded-lg p-3">
            <div className="text-xs font-mono uppercase text-fuchsia-400 mb-2">Protocol Morphing Actions</div>
            <div className="space-y-1 text-xs font-mono">
              {[
                { action: 'GHOST-SHARD',  count: '48,203 payloads fragmented' },
                { action: 'DECEPTICON',   count: '12,847 WebRTC-mimicked' },
                { action: 'STREAM-SHATTER', count: '3,291 ghost packets injected' },
              ].map(m => (
                <div key={m.action} className="flex justify-between">
                  <span className="text-fuchsia-400">{m.action}</span>
                  <span className="text-slate-400">{m.count}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
