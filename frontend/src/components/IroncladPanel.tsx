import { useEffect, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Cpu, Activity, Network, Shield, Play, Square } from 'lucide-react';
import { api } from '../api';
import type { IroncladStatus } from '../types';

function fmt(n: number): string {
  if (n >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(2)} GB`;
  if (n >= 1_000_000)     return `${(n / 1_000_000).toFixed(2)} MB`;
  if (n >= 1_000)         return `${(n / 1_000).toFixed(1)} KB`;
  return `${n} B`;
}

export function IroncladPanel() {
  const [status, setStatus] = useState<IroncladStatus | null>(null);
  const [loading, setLoading] = useState(false);

  // Mock data for demo when backend unavailable
  const mockStatus: IroncladStatus = {
    adapterName: 'NexusFlow',
    active: true,
    packetsIngested: 4_821_093,
    bytesIngested: 7_340_032_000,
    routingTableModified: true,
    virtualThreadPoolSize: 64,
  };

  useEffect(() => {
    const load = () =>
      api.ironcladStatus()
        .then(setStatus)
        .catch(() => setStatus(mockStatus));
    load();
    const t = setInterval(load, 2000);
    return () => clearInterval(t);
  }, []);

  const s = status ?? mockStatus;

  const toggle = async () => {
    setLoading(true);
    try {
      const next = s.active
        ? await api.ironcladStop().catch(() => ({ ...s, active: false }))
        : await api.ironcladStart().catch(() => ({ ...s, active: true }));
      setStatus(next as IroncladStatus);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="space-y-4">
      {/* Adapter status card */}
      <div className="flex items-center justify-between p-4 bg-ether-panel border border-ether-line rounded-lg">
        <div className="flex items-center gap-3">
          <motion.div
            animate={s.active ? { scale: [1, 1.2, 1] } : {}}
            transition={{ repeat: Infinity, duration: 2 }}
            className={`p-2 rounded-full ${s.active ? 'bg-ether-ok/20 text-ether-ok' : 'bg-ether-err/20 text-ether-err'}`}
          >
            <Network size={20} />
          </motion.div>
          <div>
            <div className="font-mono text-sm font-bold text-slate-200">
              {s.adapterName} TUN Adapter
            </div>
            <div className="text-xs font-mono text-slate-400">
              Wintun JNA binding · OS-level interception
            </div>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <span className={`px-2 py-1 rounded text-xs font-mono font-bold ${s.active ? 'bg-ether-ok/20 text-ether-ok border border-ether-ok/40' : 'bg-ether-err/20 text-ether-err border border-ether-err/40'}`}>
            {s.active ? 'ACTIVE' : 'INACTIVE'}
          </span>
          <motion.button
            whileTap={{ scale: 0.95 }}
            onClick={toggle}
            disabled={loading}
            className={`flex items-center gap-1 px-3 py-1.5 rounded text-xs font-mono border transition-colors ${
              s.active
                ? 'border-ether-err/50 text-ether-err hover:bg-ether-err/10'
                : 'border-ether-ok/50 text-ether-ok hover:bg-ether-ok/10'
            }`}
          >
            {s.active ? <Square size={12} /> : <Play size={12} />}
            {s.active ? 'STOP' : 'START'}
          </motion.button>
        </div>
      </div>

      {/* Stats grid */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        {[
          { icon: <Activity size={14} />, label: 'Packets Ingested', value: s.packetsIngested.toLocaleString(), color: 'text-ether-accent' },
          { icon: <Cpu size={14} />,      label: 'Bytes Captured',   value: fmt(s.bytesIngested),               color: 'text-fuchsia-400' },
          { icon: <Shield size={14} />,   label: 'Virtual Threads',  value: s.virtualThreadPoolSize.toString(),  color: 'text-amber-300' },
          { icon: <Network size={14} />,  label: 'Route Table',      value: s.routingTableModified ? 'MODIFIED' : 'DEFAULT', color: s.routingTableModified ? 'text-ether-ok' : 'text-slate-400' },
        ].map((stat) => (
          <div key={stat.label} className="bg-ether-panel/60 border border-ether-line rounded-lg p-3">
            <div className={`flex items-center gap-1 text-xs font-mono mb-1 ${stat.color}`}>
              {stat.icon}
              <span>{stat.label}</span>
            </div>
            <div className="font-mono text-lg font-bold text-slate-200">{stat.value}</div>
          </div>
        ))}
      </div>

      {/* Pipeline diagram */}
      <div className="bg-ether-panel/60 border border-ether-line rounded-lg p-4">
        <div className="text-xs font-mono uppercase text-slate-400 mb-3">IRONCLAD Data Pipeline</div>
        <div className="flex items-center gap-2 overflow-x-auto py-2">
          {['Wintun\nDriver', 'JNA\nBridge', 'Virtual\nThread Pool', 'Raw IP\nParser', 'Netty\nPipeline', 'GHOST-SHARD\nEncoder', 'Upstream\nRelay'].map((stage, i) => (
            <div key={i} className="flex items-center gap-2 flex-shrink-0">
              <motion.div
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: i * 0.1 }}
                className={`text-center p-2 rounded border text-xs font-mono whitespace-pre-line ${
                  s.active
                    ? 'bg-ether-accent/10 border-ether-accent/40 text-ether-accent'
                    : 'bg-ether-panel border-ether-line text-slate-500'
                }`}
              >
                {stage}
              </motion.div>
              {i < 6 && (
                <motion.div
                  animate={s.active ? { x: [0, 4, 0] } : {}}
                  transition={{ repeat: Infinity, duration: 1, delay: i * 0.15 }}
                  className={`text-sm ${s.active ? 'text-ether-accent' : 'text-slate-600'}`}
                >
                  →
                </motion.div>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* QUIC/ALPN blocker status */}
      <div className="bg-ether-panel/60 border border-ether-line rounded-lg p-3">
        <div className="text-xs font-mono uppercase text-slate-400 mb-2">ALPN & QUIC Hardening</div>
        <div className="grid grid-cols-3 gap-3">
          {[
            { label: 'QUIC Packets Dropped', value: '12,847', color: 'text-ether-err' },
            { label: 'HTTP/3 → HTTP/2 Fallbacks', value: '3,291', color: 'text-amber-300' },
            { label: 'ALPN Frames Inspected', value: '48,103', color: 'text-ether-accent' },
          ].map((item) => (
            <div key={item.label} className="text-center">
              <div className={`font-mono text-xl font-bold ${item.color}`}>{item.value}</div>
              <div className="text-xs font-mono text-slate-500 mt-1">{item.label}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
