import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { Activity, Flame, Globe2, PieChart, Radio, Rewind, Shield, Waves, Zap } from 'lucide-react';
import { api } from './api';
import { useForensicsStream } from './useForensicsStream';
import { ForensicsConsole } from './components/ForensicsConsole';
import { LanePanel } from './components/LanePanel';
import { ProviderPanel } from './components/ProviderPanel';
import { ThroughputChart } from './components/ThroughputChart';
import { ChaosPanel } from './components/ChaosPanel';
import { GeoGlobe } from './components/GeoGlobe';
import { TrafficPie } from './components/TrafficPie';
import { DvrPanel } from './components/DvrPanel';
import { PolicyEditor } from './components/PolicyEditor';
import type { LanesResponse, ProviderView } from './types';

type Tab = 'overview' | 'globe' | 'chaos' | 'classify' | 'dvr' | 'policy';

const TABS: { id: Tab; label: string; icon: React.ReactNode }[] = [
  { id: 'overview', label: 'Overview',  icon: <Activity size={14} /> },
  { id: 'globe',    label: 'Globe',     icon: <Globe2 size={14} /> },
  { id: 'chaos',    label: 'Chaos',     icon: <Flame size={14} /> },
  { id: 'classify', label: 'Classify',  icon: <PieChart size={14} /> },
  { id: 'dvr',      label: 'DVR',       icon: <Rewind size={14} /> },
  { id: 'policy',   label: 'Policy',    icon: <Shield size={14} /> },
];

export default function App() {
  const { events, connected } = useForensicsStream();
  const [lanes, setLanes] = useState<LanesResponse | null>(null);
  const [providers, setProviders] = useState<ProviderView[]>([]);
  const [tab, setTab] = useState<Tab>('overview');

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
      <Header connected={connected} tab={tab} setTab={setTab} />

      <main className="flex-1 p-4">
        {tab === 'overview' && (
          <div className="grid grid-cols-12 gap-4">
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
          </div>
        )}

        {tab === 'globe' && (
          <Panel title="Geo-Topology" icon={<Globe2 className="text-ether-accent" size={16} />}>
            <GeoGlobe events={events} />
          </Panel>
        )}

        {tab === 'chaos' && (
          <div className="grid grid-cols-12 gap-4">
            <section className="col-span-12 lg:col-span-7">
              <Panel title="Chaos Engine" icon={<Flame className="text-ether-err" size={16} />}>
                <ChaosPanel lanes={lanes} />
              </Panel>
            </section>
            <section className="col-span-12 lg:col-span-5">
              <Panel title="Lane Health (live)" icon={<Waves className="text-ether-accent" size={16} />}>
                <LanePanel data={lanes} onStrategy={(s) => api.setStrategy(s).catch(() => {})} />
              </Panel>
            </section>
          </div>
        )}

        {tab === 'classify' && (
          <div className="grid grid-cols-12 gap-4">
            <section className="col-span-12 lg:col-span-6">
              <Panel title="Traffic Classification" icon={<PieChart className="text-ether-accent" size={16} />}>
                <TrafficPie />
              </Panel>
            </section>
            <section className="col-span-12 lg:col-span-6">
              <Panel title="Throughput" icon={<Activity className="text-ether-accent" size={16} />}>
                <ThroughputChart events={events} />
              </Panel>
            </section>
          </div>
        )}

        {tab === 'dvr' && (
          <Panel title="Time-Travel Forensics" icon={<Rewind className="text-ether-accent" size={16} />}>
            <DvrPanel />
          </Panel>
        )}

        {tab === 'policy' && (
          <Panel title="Routing Policy DSL" icon={<Shield className="text-ether-accent" size={16} />}>
            <PolicyEditor />
          </Panel>
        )}
      </main>
    </div>
  );
}

function Header({
  connected, tab, setTab,
}: { connected: boolean; tab: Tab; setTab: (t: Tab) => void }) {
  return (
    <header className="border-b border-ether-line bg-ether-panel/60 backdrop-blur px-6 py-3 flex items-center justify-between gap-6">
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
      <nav className="flex gap-1 overflow-x-auto">
        {TABS.map((t) => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={`flex items-center gap-1 px-3 py-1 rounded text-xs font-mono uppercase tracking-wider transition-colors ${
              tab === t.id
                ? 'bg-ether-accent/15 text-ether-accent border border-ether-accent/40'
                : 'text-slate-400 hover:text-slate-200 border border-transparent'
            }`}
          >
            {t.icon}<span>{t.label}</span>
          </button>
        ))}
      </nav>
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
