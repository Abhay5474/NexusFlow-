import type { ProviderView } from '../types';

export function ProviderPanel({ providers }: { providers: ProviderView[] }) {
  if (providers.length === 0)
    return <div className="text-slate-600 text-sm italic">loading providers…</div>;
  return (
    <div className="flex flex-col gap-2">
      {providers.map((p) => (
        <div key={p.id} className="flex items-center justify-between text-xs font-mono">
          <div>
            <div className="text-slate-200">{p.id}</div>
            <div className="text-slate-500">{p.endpoint}</div>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-24 h-1.5 bg-ether-line rounded overflow-hidden">
              <div
                className="h-full bg-ether-accent glow-line"
                style={{ width: `${Math.max(0, Math.min(1, p.score)) * 100}%` }}
              />
            </div>
            <span className="text-ether-accent w-10 text-right">
              {p.score.toFixed(2)}
            </span>
          </div>
        </div>
      ))}
    </div>
  );
}
