import { useEffect, useState } from 'react';
import Editor from '@monaco-editor/react';
import { Shield, Check, AlertTriangle } from 'lucide-react';
import { api } from '../api';

const TEMPLATE = `{
  "rules": [
    { "if": { "port": 25 },                              "action": "deny",  "reason": "smtp blocked" },
    { "if": { "domainEndsWith": ".internal" },           "action": "pin",   "lane": 0 },
    { "if": { "domainContains": "ads", "afterHour": 22 }, "action": "deny", "reason": "night-block" },
    { "if": { "any": true },                             "action": "allow" }
  ]
}`;

export function PolicyEditor() {
  const [source, setSource] = useState<string>(TEMPLATE);
  const [status, setStatus] = useState<{ ok: boolean; msg: string; version?: number } | null>(null);

  useEffect(() => {
    api.policySource().then((p) => {
      if (p.source && p.source.trim().length > 0) setSource(p.source);
    }).catch(() => {});
  }, []);

  const install = async () => {
    try {
      const r = await api.installPolicy(source);
      setStatus({ ok: r.ok, msg: r.ok ? `installed v${r.version}` : (r.error ?? 'error'), version: r.version });
    } catch (e: any) {
      setStatus({ ok: false, msg: String(e?.message ?? e) });
    }
  };

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <div className="text-xs font-mono text-slate-400 flex items-center gap-2">
          <Shield size={14} className="text-ether-accent" />
          Zero-Trust Routing DSL (JSON)
        </div>
        <button
          onClick={install}
          className="px-3 py-1 rounded font-mono text-xs uppercase tracking-wider bg-ether-accent/20 text-ether-accent border border-ether-accent"
        >
          Install
        </button>
      </div>
      <div className="border border-ether-line rounded overflow-hidden" style={{ height: 320 }}>
        <Editor
          height="100%"
          defaultLanguage="json"
          value={source}
          theme="vs-dark"
          options={{
            fontSize: 12, fontFamily: 'ui-monospace',
            minimap: { enabled: false }, scrollBeyondLastLine: false,
          }}
          onChange={(v) => setSource(v ?? '')}
        />
      </div>
      {status && (
        <div className={`flex items-center gap-2 text-xs font-mono ${status.ok ? 'text-ether-ok' : 'text-ether-err'}`}>
          {status.ok ? <Check size={14} /> : <AlertTriangle size={14} />}
          <span>{status.msg}</span>
        </div>
      )}
    </div>
  );
}
