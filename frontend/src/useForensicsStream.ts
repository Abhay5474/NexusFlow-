import { useEffect, useRef, useState } from 'react';
import type { ForensicsEvent } from './types';

const MAX_EVENTS = 500;

export function useForensicsStream(): {
  events: ForensicsEvent[];
  connected: boolean;
} {
  const [events, setEvents] = useState<ForensicsEvent[]>([]);
  const [connected, setConnected] = useState(false);
  const buf = useRef<ForensicsEvent[]>([]);

  useEffect(() => {
    const ws = new WebSocket(`ws://${window.location.host}/ws/forensics`);
    ws.onopen  = () => setConnected(true);
    ws.onclose = () => setConnected(false);
    ws.onerror = () => setConnected(false);
    ws.onmessage = (m) => {
      try {
        const ev = JSON.parse(m.data) as ForensicsEvent;
        buf.current = [ev, ...buf.current].slice(0, MAX_EVENTS);
      } catch { /* ignore malformed */ }
    };

    const tick = setInterval(() => setEvents(buf.current), 250);
    return () => { clearInterval(tick); ws.close(); };
  }, []);

  return { events, connected };
}
