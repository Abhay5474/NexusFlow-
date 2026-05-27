import { useState, useCallback } from 'react';
import Joyride, { type CallBackProps, STATUS, type Step } from 'react-joyride';
import { motion, AnimatePresence } from 'framer-motion';
import { BookOpen, X, ChevronRight } from 'lucide-react';

const TOUR_STEPS: Step[] = [
  {
    target: 'body',
    placement: 'center',
    disableBeacon: true,
    title: '🛡️ Welcome to NexusFlow NOC',
    content: (
      <div className="space-y-2 text-sm">
        <p>This is your <strong>Active Counter-Surveillance Networking Overlay</strong> — a production-grade OS-level security appliance.</p>
        <p>Let's walk through every active defense system currently protecting your traffic.</p>
      </div>
    ),
  },
  {
    target: '#ironclad-tab',
    placement: 'bottom',
    title: '⚙️ Project IRONCLAD — TUN Adapter',
    content: (
      <div className="space-y-2 text-sm">
        <p><strong>Zero-Config TUN Virtual Adapter Interface.</strong></p>
        <p>NexusFlow uses JNA to bind to <code>wintun.dll</code>, creating a virtual "NexusFlow" network adapter at the OS level. <strong>100% of global IP traffic</strong> is routed through it via OS routing table modifications.</p>
        <p>Raw IP packets are processed in a high-speed Java virtual thread loop before entering the Netty pipeline.</p>
      </div>
    ),
  },
  {
    target: '#ironclad-status',
    placement: 'bottom',
    title: '📡 Wintun Adapter Status',
    content: (
      <div className="space-y-2 text-sm">
        <p>This card shows the live status of the NexusFlow virtual adapter — whether it's intercepting all OS traffic or paused.</p>
        <p>The green indicator means <strong>all IP packets are flowing through your analysis engine</strong> before hitting the internet.</p>
      </div>
    ),
  },
  {
    target: '#ids-tab',
    placement: 'bottom',
    title: '🔍 Project SENTINEL-IDS',
    content: (
      <div className="space-y-2 text-sm">
        <p><strong>Embedded Local Network IDS/IPS</strong> running at wire-speed inside Netty.</p>
        <p>Uses an <strong>Aho-Corasick multi-pattern matcher</strong> to stream-scan payloads against 47,000+ threat signatures in real time. Matching packets are <strong>immediately dropped</strong> from the pipeline.</p>
      </div>
    ),
  },
  {
    target: '#aegis-tab',
    placement: 'bottom',
    title: '🛑 Project AEGIS — Ad & Tracker Sinkhole',
    content: (
      <div className="space-y-2 text-sm">
        <p><strong>OS-wide ad blocking without browser extensions.</strong></p>
        <p>A <strong>lock-free Radix Trie</strong> stores 1.8M+ blocklist entries (EasyList, EasyPrivacy). Hostnames are intercepted at the request phase and dropped before the browser even makes a connection.</p>
        <p><strong>Project BLACK-HOLE</strong> goes further — it returns fake HTTP 200 OK responses to telemetry endpoints, fooling apps into thinking they've reported successfully.</p>
      </div>
    ),
  },
  {
    target: '#aegis-block-rate',
    placement: 'top',
    title: '📊 AEGIS Block Rate Counter',
    content: (
      <div className="space-y-2 text-sm">
        <p>This live counter shows how many ad/tracker domains have been blocked since startup.</p>
        <p>YouTube ad bidding servers (<code>pagead2.googlesyndication.com</code>), Facebook pixels, and Google Analytics are blocked at the <strong>network layer</strong> — zero-extension, zero-browser modification required.</p>
      </div>
    ),
  },
  {
    target: '#counter-score-tab',
    placement: 'bottom',
    title: '🖥️ Project COUNTER-SCORE — XKeyscore Dashboard',
    content: (
      <div className="space-y-2 text-sm">
        <p>Your local <strong>XKeyscore-style forensics grid</strong>, grouped by Process ID.</p>
        <p>Every application on your system is tracked by PID — showing bytes in/out, blocked requests, active connections, and a real-time <strong>threat score</strong>.</p>
        <p>Suspicious background processes (high outbound / low inbound ratio) are flagged automatically.</p>
      </div>
    ),
  },
  {
    target: '#ghost-shard-info',
    placement: 'top',
    title: '💥 Project GHOST-SHARD — Anti-DPI Fragmentation',
    content: (
      <div className="space-y-2 text-sm">
        <p>A Netty <code>MessageToMessageEncoder</code> that <strong>defeats Deep Packet Inspection</strong> by slicing outbound TCP payloads into randomized, irregular byte sizes.</p>
        <p>Cryptographic noise/padding is injected, transforming TLS ClientHellos and other recognizable protocol signatures into <strong>unclassifiable binary white noise</strong>.</p>
      </div>
    ),
  },
  {
    target: '#stream-shatter-info',
    placement: 'top',
    title: '🌊 Project STREAM-SHATTER — Anti-Tap Defense',
    content: (
      <div className="space-y-2 text-sm">
        <p>Neutralizes <strong>passive fiber-optic tap splitters</strong> and bulk packet reassemblers.</p>
        <p>Ghost packets with overlapping TCP sequence numbers and intentionally corrupted checksums are injected onto the wire. Destination servers discard them cleanly, but <strong>external sniffers choke</strong> trying to reassemble the chaotic stream.</p>
      </div>
    ),
  },
  {
    target: 'body',
    placement: 'center',
    title: '✅ You are protected.',
    content: (
      <div className="space-y-2 text-sm">
        <p>NexusFlow's full defense stack is now active:</p>
        <ul className="list-disc list-inside space-y-1 text-xs">
          <li>OS-level packet interception (IRONCLAD)</li>
          <li>QUIC/ALPN blocking → TCP fallback</li>
          <li>Anti-DPI byte fragmentation (GHOST-SHARD)</li>
          <li>Ghost packet injection (STREAM-SHATTER)</li>
          <li>Protocol polymorphism (DECEPTICON)</li>
          <li>Real-time IDS/IPS (SENTINEL)</li>
          <li>Beacon detection (EWMA behavioral analysis)</li>
          <li>Process sandbox (SAFE-ZONE)</li>
          <li>Line-tap detection (SHIELD-FABRIC)</li>
          <li>OS-wide ad/tracker blocking (AEGIS)</li>
          <li>Telemetry sinkholing (BLACK-HOLE)</li>
          <li>Exfiltration guard (clipboard/POST scan)</li>
          <li>Off-heap ring buffer forensics (DEEP-TRACE)</li>
        </ul>
      </div>
    ),
  },
];

const joyrideStyles = {
  options: {
    primaryColor: '#56d4ff',
    backgroundColor: '#0f172a',
    textColor: '#e2e8f0',
    arrowColor: '#0f172a',
    overlayColor: 'rgba(0,0,0,0.7)',
    zIndex: 10000,
  },
  tooltip: {
    fontFamily: 'ui-monospace, SFMono-Regular, monospace',
    fontSize: '13px',
    padding: '16px',
    borderRadius: '8px',
    border: '1px solid #334155',
    maxWidth: '380px',
  },
  tooltipTitle: {
    fontSize: '14px',
    fontWeight: 700,
    marginBottom: '8px',
    color: '#56d4ff',
  },
  buttonNext: {
    background: 'rgba(86, 212, 255, 0.2)',
    border: '1px solid rgba(86, 212, 255, 0.5)',
    color: '#56d4ff',
    borderRadius: '4px',
    fontSize: '12px',
    fontFamily: 'ui-monospace, SFMono-Regular, monospace',
    padding: '6px 12px',
  },
  buttonBack: {
    color: '#64748b',
    fontSize: '12px',
    fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  },
  buttonSkip: {
    color: '#64748b',
    fontSize: '12px',
    fontFamily: 'ui-monospace, SFMono-Regular, monospace',
  },
};

interface NexusGuideProps {
  onTabChange?: (tab: string) => void;
}

export function NexusGuide({ onTabChange }: NexusGuideProps) {
  const [running, setRunning] = useState(false);
  const [stepIndex, setStepIndex] = useState(0);

  const handleJoyrideCallback = useCallback((data: CallBackProps) => {
    const { status, index, type } = data;
    if (type === 'step:before') {
      setStepIndex(index);
      // Navigate to relevant tab based on step
      if (index === 1 || index === 2) onTabChange?.('ironclad');
      else if (index === 3)           onTabChange?.('ids');
      else if (index === 4 || index === 5) onTabChange?.('aegis');
      else if (index === 6 || index === 7 || index === 8) onTabChange?.('counter');
    }
    if ([STATUS.FINISHED, STATUS.SKIPPED].includes(status as any)) {
      setRunning(false);
      setStepIndex(0);
    }
  }, [onTabChange]);

  return (
    <>
      <Joyride
        steps={TOUR_STEPS}
        run={running}
        stepIndex={stepIndex}
        continuous
        showSkipButton
        showProgress
        scrollToFirstStep
        styles={joyrideStyles as any}
        callback={handleJoyrideCallback}
        locale={{
          back: '← Back',
          close: 'Close',
          last: 'Finish Tour',
          next: 'Next →',
          skip: 'Skip Tour',
        }}
      />

      <AnimatePresence>
        {!running && (
          <motion.button
            initial={{ opacity: 0, scale: 0.8 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.8 }}
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.95 }}
            onClick={() => { setStepIndex(0); setRunning(true); }}
            className="flex items-center gap-2 px-3 py-1.5 bg-ether-accent/15 border border-ether-accent/40 text-ether-accent rounded text-xs font-mono hover:bg-ether-accent/25 transition-colors"
          >
            <BookOpen size={12} />
            <span>NEXUS GUIDE</span>
            <ChevronRight size={10} />
          </motion.button>
        )}
        {running && (
          <motion.button
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => setRunning(false)}
            className="flex items-center gap-1 px-3 py-1.5 bg-ether-err/15 border border-ether-err/40 text-ether-err rounded text-xs font-mono"
          >
            <X size={12} />
            <span>STOP TOUR</span>
          </motion.button>
        )}
      </AnimatePresence>
    </>
  );
}
