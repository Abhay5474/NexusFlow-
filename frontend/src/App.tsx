import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { Activity, Radio, Shield, Waves, Zap } from 'lucide-react';
import { api } from './api';
import { useForensicsStream } from './useForensicsStream';
import { ForensicsConsole } from './components/ForensicsConsole';
import { LanePanel } from './components/LanePanel';
import { ProviderPanel } from './components/ProviderPanel';
import { ThroughputChart } from './components/ThroughputChart';
import type { LanesResponse, ProviderView } from './types';

export default function App() {
  const { events, connected } = useForensicsStream();
  const [lanes, setLanes] = useState<LanesResponse | null>(null);
  const [providers, setProviders] = useState<ProviderView[]>([]);

  useEffect(() => {
    const refresh = () => {
      api.lanes().then(setLanes).catch(() => {});
      api.providers().then(setProviders).catch(() => {});
    };
    refresh();
    const t = setInterval(refresh, 2000);
    return () => clearInterval(t);
  }, []);

  return (
    <div className="min-h-screen flex flex-col">
      <Header connected={connected} />
      <main className="flex-1 grid grid-cols-12 gap-4 p-4">
        <section className="col-span-12 lg:col-span-8 flex flex-col gap-4">
          <Panel title="Lanes" icon={<Waves className="text-ether-accent" size={16} />}>
            <LanePanel
              data={lanes}
              onStrategy={(s) =>
                api.setStrategy(s).then((r) =>
                  setLanes((cur) => (cur ? { ...cur, strategy: r.strategy } : cur)))
              }
            />
          </Panel>
          <Panel title="Throughput" icon={<Activity className="text-ether-accent" size={16} />}>
            <ThroughputChart events={events} />
          </Panel>
        </section>
        <section className="col-span-12 lg:col-span-4 flex flex-col gap-4">
          <Panel title="DNS Providers" icon={<Shield className="text-ether-accent" size={16} />}>
            <ProviderPanel providers={providers} />
          </Panel>
          <Panel title="Forensics Stream" icon={<Radio className="text-ether-accent" size={16} />}>
            <ForensicsConsole events={events} />
          </Panel>
        </section>
      </main>
    </div>
  );
}

function Header({ connected }: { connected: boolean }) {
  return (
    <header className="border-b border-ether-line bg-ether-panel/60 backdrop-blur px-6 py-3 flex items-center justify-between">
      <div className="flex items-center gap-3">
        <motion.div
          animate={{ rotate: 360 }}
          transition={{ repeat: Infinity, duration: 12, ease: 'linear' }}
          className="text-ether-accent"
        >
          <Zap size={20} />
        </motion.div>
        <div className="font-mono tracking-widest">
          <span className="text-ether-accent">AetherOS</span>
          <span className="text-slate-500"> · </span>
          <span className="text-slate-300">NexusFlow NOC</span>
        </div>
      </div>
      <div className="flex items-center gap-2 text-xs font-mono">
        <span className={`h-2 w-2 rounded-full ${connected ? 'bg-ether-ok' : 'bg-ether-err'}`} />
        <span className="text-slate-400">
          {connected ? 'forensics: connected' : 'forensics: disconnected'}
        </span>
      </div>
    </header>
  );
}

function Panel({
  title, icon, children,
}: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="bg-ether-panel/70 border border-ether-line rounded-lg overflow-hidden flex flex-col">
      <div className="px-4 py-2 border-b border-ether-line flex items-center gap-2 text-xs font-mono uppercase tracking-wider text-slate-400">
        {icon}
        <span>{title}</span>
      </div>
      <div className="p-3 flex-1 min-h-0">{children}</div>
    </div>
  );
}
