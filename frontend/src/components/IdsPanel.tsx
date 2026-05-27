import { useEffect, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { AlertTriangle, Shield, Eye, Zap, Activity } from 'lucide-react';
import { api } from '../api';
import type { IdsAlert } from '../types';

const SEV_COLOR = {
  LOW:      'text-slate-400 border-slate-600 bg-slate-800/40',
  MEDIUM:   'text-amber-300 border-amber-600/50 bg-amber-900/20',
  HIGH:     'text-orange-400 border-orange-600/50 bg-orange-900/20',
  CRITICAL: 'text-ether-err border-ether-err/50 bg-red-900/20',
};

const MOCK_ALERTS: IdsAlert[] = [
  { id: '1', ts: new Date(Date.now() - 2000).toISOString(),  ruleId: 'ET-2019401', severity: 'CRITICAL', payload: '\\x00\\x00\\x00\\x00shellcode_nop_sled', action: 'DROPPED',  srcIp: '185.220.101.47', dstPort: 443 },
  { id: '2', ts: new Date(Date.now() - 8000).toISOString(),  ruleId: 'ET-2001219', severity: 'HIGH',     payload: 'UNION SELECT NULL,NULL,NULL--',          action: 'DROPPED',  srcIp: '45.142.212.100', dstPort: 80 },
  { id: '3', ts: new Date(Date.now() - 15000).toISOString(), ruleId: 'ET-2018752', severity: 'MEDIUM',   payload: 'X-Forwarded-For: 127.0.0.1',              action: 'LOGGED',   srcIp: '104.21.55.12',  dstPort: 8080 },
  { id: '4', ts: new Date(Date.now() - 23000).toISOString(), ruleId: 'ET-2001045', severity: 'LOW',      payload: 'User-Agent: zgrab/0.x',                  action: 'LOGGED',   srcIp: '209.141.36.20', dstPort: 443 },
  { id: '5', ts: new Date(Date.now() - 45000).toISOString(), ruleId: 'ET-2019855', severity: 'HIGH',     payload: '../../../etc/passwd',                    action: 'DROPPED',  srcIp: '193.239.84.200', dstPort: 80 },
];

export function IdsPanel() {
  const [alerts, setAlerts] = useState<IdsAlert[]>(MOCK_ALERTS);
  const [filter, setFilter] = useState<string>('ALL');

  useEffect(() => {
    const load = () =>
      api.idsAlerts()
        .then(setAlerts)
        .catch(() => setAlerts(MOCK_ALERTS));
    load();
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, []);

  const filtered = filter === 'ALL' ? alerts : alerts.filter(a => a.severity === filter);
  const dropped  = alerts.filter(a => a.action === 'DROPPED').length;
  const critical = alerts.filter(a => a.severity === 'CRITICAL').length;

  return (
    <div className="space-y-4">
      {/* Stats */}
      <div className="grid grid-cols-4 gap-3">
        {[
          { icon: <Shield size={14}/>,        label: 'Total Alerts',     value: alerts.length,  color: 'text-ether-accent' },
          { icon: <AlertTriangle size={14}/>,  label: 'Critical',         value: critical,        color: 'text-ether-err' },
          { icon: <Zap size={14}/>,            label: 'Packets Dropped',  value: dropped,         color: 'text-amber-300' },
          { icon: <Activity size={14}/>,       label: 'Sig. DB Size',     value: '47,218',        color: 'text-fuchsia-400' },
        ].map(s => (
          <div key={s.label} className="bg-ether-panel/60 border border-ether-line rounded-lg p-3 text-center">
            <div className={`flex items-center justify-center gap-1 text-xs font-mono mb-1 ${s.color}`}>
              {s.icon}<span>{s.label}</span>
            </div>
            <div className="font-mono text-2xl font-bold text-slate-200">{s.value}</div>
          </div>
        ))}
      </div>

      {/* Severity filter */}
      <div className="flex gap-2">
        {['ALL','LOW','MEDIUM','HIGH','CRITICAL'].map(sev => (
          <button
            key={sev}
            onClick={() => setFilter(sev)}
            className={`px-2 py-1 rounded text-xs font-mono border transition-colors ${
              filter === sev
                ? 'bg-ether-accent/20 border-ether-accent/60 text-ether-accent'
                : 'border-ether-line text-slate-500 hover:text-slate-300'
            }`}
          >{sev}</button>
        ))}
      </div>

      {/* Alert table */}
      <div className="space-y-1.5 max-h-[400px] overflow-y-auto">
        <AnimatePresence>
          {filtered.map((alert) => (
            <motion.div
              key={alert.id}
              initial={{ opacity: 0, x: -10 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: 10 }}
              className={`flex items-center gap-3 p-3 rounded-lg border font-mono text-xs ${SEV_COLOR[alert.severity]}`}
            >
              <span className="text-slate-500 flex-shrink-0">{alert.ts.slice(11, 23)}</span>
              <span className="font-bold flex-shrink-0 w-24">{alert.ruleId}</span>
              <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold flex-shrink-0 ${
                alert.action === 'DROPPED' ? 'bg-ether-err/20 text-ether-err' : 'bg-slate-700 text-slate-400'
              }`}>{alert.action}</span>
              <span className="text-slate-500 flex-shrink-0">{alert.srcIp}:{alert.dstPort}</span>
              <span className="truncate text-slate-300">{alert.payload}</span>
            </motion.div>
          ))}
        </AnimatePresence>
      </div>

      {/* Beacon detector */}
      <div className="bg-ether-panel/60 border border-amber-600/30 rounded-lg p-3">
        <div className="text-xs font-mono uppercase text-amber-400 mb-2 flex items-center gap-1">
          <Eye size={12} /> Behavioral Beacon Detector (EWMA)
        </div>
        <div className="space-y-1.5">
          {[
            { host: 'telemetry.windows.com',       interval: 30000, score: 0.94, action: 'DISCONNECTED' },
            { host: 'collector.github.com',         interval: 60000, score: 0.71, action: 'FLAGGED' },
            { host: 'metrics.sentry.io',            interval: 15000, score: 0.88, action: 'FLAGGED' },
          ].map((b) => (
            <div key={b.host} className="flex items-center gap-2 text-xs font-mono">
              <span className={`px-1 py-0.5 rounded text-[10px] font-bold ${
                b.action === 'DISCONNECTED' ? 'bg-ether-err/20 text-ether-err' : 'bg-amber-900/30 text-amber-400'
              }`}>{b.action}</span>
              <span className="text-slate-300 flex-1">{b.host}</span>
              <span className="text-slate-500">∆ {(b.interval/1000).toFixed(0)}s interval</span>
              <span className="text-amber-300 font-bold">score: {b.score.toFixed(2)}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
