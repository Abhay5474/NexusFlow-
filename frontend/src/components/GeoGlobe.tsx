import { useEffect, useMemo, useRef, useState } from 'react';
import Globe from 'react-globe.gl';
import type { ForensicsEvent, GeoArc } from '../types';

// "Home" coordinates default to a neutral mid-Atlantic point until the user
// configures their own. Override via localStorage('aetheros.home').
function loadHome(): { lat: number; lon: number } {
  try {
    const raw = localStorage.getItem('aetheros.home');
    if (raw) return JSON.parse(raw);
  } catch {}
  return { lat: 20, lon: 0 };
}

const ARC_TTL_MS = 8_000;

export function GeoGlobe({ events }: { events: ForensicsEvent[] }) {
  const home = useMemo(loadHome, []);
  const [arcs, setArcs] = useState<GeoArc[]>([]);
  const seen = useRef(new Set<string>());

  useEffect(() => {
    const fresh: GeoArc[] = [];
    for (const e of events) {
      if (e.stage !== 'DNS') continue;
      const t = e.tags as Record<string, unknown>;
      if (typeof t.lat !== 'number' || typeof t.lon !== 'number') continue;
      const key = e.connectionId + '|' + t.ip;
      if (seen.current.has(key)) continue;
      seen.current.add(key);
      fresh.push({
        id: key, ts: Date.parse(e.ts),
        lat: t.lat as number, lon: t.lon as number,
        country: String(t.country ?? ''),
        city:    String(t.city ?? ''),
        domain:  String(t.domain ?? ''),
      });
    }
    if (fresh.length > 0) setArcs((cur) => [...cur, ...fresh]);

    const cutoff = Date.now() - ARC_TTL_MS;
    setArcs((cur) => cur.filter((a) => a.ts >= cutoff));
  }, [events]);

  const arcData = arcs.map((a) => ({
    startLat: home.lat, startLng: home.lon,
    endLat: a.lat, endLng: a.lon,
    label: `${a.domain} → ${a.country}${a.city ? ' / ' + a.city : ''}`,
  }));

  return (
    <div className="h-[480px] flex items-center justify-center bg-ether-bg/40 rounded border border-ether-line">
      <Globe
        backgroundColor="rgba(0,0,0,0)"
        globeImageUrl="//unpkg.com/three-globe/example/img/earth-night.jpg"
        atmosphereColor="#56d4ff"
        arcsData={arcData}
        arcColor={() => ['#56d4ff', '#4be3a3']}
        arcDashLength={0.4}
        arcDashGap={0.1}
        arcDashAnimateTime={1500}
        arcStroke={0.5}
        arcLabel={(d: any) => d.label}
        width={520} height={460}
      />
    </div>
  );
}
