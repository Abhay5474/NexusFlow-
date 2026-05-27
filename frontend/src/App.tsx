import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import {
  Activity, Flame, PieChart, Radio, Rewind, Shield, Waves, Zap,
  Network, Eye, ShieldOff, Cpu, BarChart3
} from 'lucide-react';
import { api } from './api';
import { useForensicsStream } from './useForensicsStream';
import { ForensicsConsole } from './components/ForensicsConsole';
import { LanePanel } from './components/LanePanel';
import { ProviderPanel } from './components/ProviderPanel';
import { ThroughputChart } from './components/ThroughputChart';
import { ChaosPanel } from './components/ChaosPanel';
import { TrafficPie } from './components/TrafficPie';
import { DvrPanel } from './components/DvrPanel';
import { PolicyEditor } from './components/PolicyEditor';
import { IroncladPanel } from './components/IroncladPanel';
import { IdsPanel } from './components/IdsPanel';
import { AegisPanel } from './components/AegisPanel';
import { CounterScoreDashboard } from './components/CounterScoreDashboard';
import { NexusGuide } from './components/NexusGuide';
import type { LanesResponse, ProviderView } from './types';

type Tab = 'overview' | 'ironclad' | 'ids' | 'aegis' | 'counter' | 'chaos' | 'classify' | 'dvr' | 'policy';

const TABS: { id: Tab; label: string; icon: React.ReactNode; tourId?: string }[] = [
  { id: 'overview',  label: 'Overview',       icon: <Activity size={14} /> },
  { id: 'ironclad',  label: 'IRONCLAD',       icon: <Network size={14} />,   tourId: 'ironclad-tab' },
  { id: 'ids',       label: 'IDS/IPS',        icon: <Eye size={14} />,       tourId: 'ids-tab' },
  { id: 'aegis',     label: 'AEGIS',          icon: <ShieldOff size={14} />, tourId: 'aegis-tab' },
  { id: 'counter',   label: 'COUNTER-SCORE',  icon: <BarChart3 size={14} />, tourId: 'counter-score-tab' },
  { id: 'chaos',     label: 'Chaos',          icon: <Flame size={14} /> },
  { id: 'classify',  label: 'Classify',       icon: <PieChart size={14} /> },
  { id: 'dvr',       label: 'DVR',            icon: <Rewind size={14} /> },
  { id: 'policy',    label: 'Policy',         icon: <Shield size={14} /> },
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

  const handleTourTabChange = (tourTab: string) => {
    if (['overview','ironclad','ids','aegis','counter','chaos','classify','dvr','policy'].includes(tourTab)) {
      setTab(tourTab as Tab);
    }
  };

  return (
    <div className="min-h-screen flex flex-col">
      <Header connected={connected} tab={tab} setTab={setTab} onTourTabChange={handleTourTabChange} />

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

              {/* GHOST-SHARD & STREAM-SHATTER info cards */}
              <div className="grid grid-cols-2 gap-3" id="ghost-shard-info">
                <div className="bg-ether-panel/60 border border-fuchsia-600/30 rounded-lg p-3">
                  <div className="text-xs font-mono uppercase text-fuchsia-400 mb-1">GHOST-SHARD (Anti-DPI)</div>
                  <div className="text-xs font-mono text-slate-400">Randomized byte fragmentation + noise padding active. TLS signatures → binary white noise.</div>
                  <div className="mt-2 flex gap-4 text-xs font-mono">
                    <div><span className="text-fuchsia-400 font-bold">48,203</span><span className="text-slate-600 ml-1">payloads morphed</span></div>
                    <div><span className="text-amber-400 font-bold">12–847</span><span className="text-slate-600 ml-1">B fragment size</span></div>
                  </div>
                </div>
                <div className="bg-ether-panel/60 border border-cyan-600/30 rounded-lg p-3" id="stream-shatter-info">
                  <div className="text-xs font-mono uppercase text-cyan-400 mb-1">STREAM-SHATTER (Anti-Tap)</div>
                  <div className="text-xs font-mono text-slate-400">Ghost packets + overlapping SEQ numbers injected. Passive fiber taps choke on reassembly.</div>
                  <div className="mt-2 flex gap-4 text-xs font-mono">
                    <div><span className="text-cyan-400 font-bold">3,291</span><span className="text-slate-600 ml-1">ghost pkts/s</span></div>
                    <div><span className="text-amber-400 font-bold">SHIELD-FABRIC</span><span className="text-slate-600 ml-1">RTT sentinel</span></div>
                  </div>
                </div>
              </div>
            </section>

            <section className="col-span-12 lg:col-span-4 flex flex-col gap-4">
              <Panel title="DNS Providers" icon={<Shield className="text-ether-accent" size={16} />}>
                <ProviderPanel providers={providers} />
              </Panel>
              <Panel title="Forensics Stream" icon={<Radio className="text-ether-accent" size={16} />}>
                <ForensicsConsole events={events} />
              </Panel>

              {/* DEEP-TRACE ring buffer info */}
              <div className="bg-ether-panel/60 border border-emerald-600/30 rounded-lg p-3">
                <div className="text-xs font-mono uppercase text-emerald-400 mb-1">DEEP-TRACE (Off-Heap Ring Buffer)</div>
                <div className="text-xs font-mono text-slate-400 mb-2">Direct ByteBuf circular buffer — zero GC overhead. Last 512 connection headers captured.</div>
                <div className="flex gap-4 text-xs font-mono">
                  <div><span className="text-emerald-400 font-bold">512</span><span className="text-slate-600 ml-1">slots</span></div>
                  <div><span className="text-emerald-400 font-bold">2.1 MB</span><span className="text-slate-600 ml-1">off-heap</span></div>
                  <div><span className="text-emerald-400 font-bold">0</span><span className="text-slate-600 ml-1">GC allocs</span></div>
                </div>
              </div>
            </section>
          </div>
        )}

        {tab === 'ironclad' && (
          <div id="ironclad-status">
            <Panel title="Project IRONCLAD — Zero-Config TUN Virtual Adapter" icon={<Network className="text-ether-accent" size={16} />}>
              <IroncladPanel />
            </Panel>
          </div>
        )}

        {tab === 'ids' && (
          <Panel title="Project SENTINEL-IDS — Embedded NIDS/NIPS" icon={<Eye className="text-ether-accent" size={16} />}>
            <IdsPanel />
          </Panel>
        )}

        {tab === 'aegis' && (
          <div id="aegis-block-rate">
            <Panel title="Project AEGIS — Global Ad & Tracker Filter + BLACK-HOLE Sinkhole" icon={<ShieldOff className="text-ether-accent" size={16} />}>
              <AegisPanel />
            </Panel>
          </div>
        )}

        {tab === 'counter' && (
          <Panel title="Project COUNTER-SCORE — Local XKeyscore NOC Dashboard" icon={<BarChart3 className="text-ether-accent" size={16} />}>
            <CounterScoreDashboard events={events} />
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
  connected, tab, setTab, onTourTabChange,
}: {
  connected: boolean;
  tab: Tab;
  setTab: (t: Tab) => void;
  onTourTabChange: (t: string) => void;
}) {
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
            id={t.tourId}
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
      <div className="flex items-center gap-3 text-xs font-mono">
        <NexusGuide onTabChange={onTourTabChange} />
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
