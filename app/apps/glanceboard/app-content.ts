/** Host-owned identities; apps declare only service-local widget IDs. */
export type AppGlanceEntry = { id: string; title: string; detail: string; expiresAt: number };
export type WidgetDeclaration = { id: string; label: string; kind: 'list' | 'scene'; rows: 1 | 2; refreshMs: number; uses: string[] };
export type AppGlanceSource = WidgetDeclaration & { key: string; component: string; connected: boolean; title: string };
export type AppGlanceContent = { version: number; widgetId?: string; title?: string; emptyText?: string; expiresAt: number; entries?: AppGlanceEntry[]; commands?: any[]; redacted?: boolean };
const sources = new Map<string, AppGlanceSource>();
const contents = new Map<string, AppGlanceContent>();
const expiryTimers = new Map<string, ReturnType<typeof setTimeout>>();
const listeners = new Set<() => void>();
const registryListeners = new Set<() => void>();
let request: (source: AppGlanceSource) => void = () => {};
export function appGlanceKey(component: string, id = 'default'): string { return id === 'default' ? component : `${component}#${id}`; }
export function configureAppGlanceRequest(callback: typeof request): void { request = callback; }
export function requestAppGlance(key: string): void { const source = sources.get(key); if (source?.connected) request(source); }
export function appGlanceSources() { return [...sources.values()]; }
export function onAppGlanceRegistryChanged(callback: () => void): () => void { registryListeners.add(callback); return () => registryListeners.delete(callback); }
export function onAppGlanceChanged(callback: () => void): () => void { listeners.add(callback); return () => listeners.delete(callback); }
function removeContent(key: string): void { clearTimeout(expiryTimers.get(key)); expiryTimers.delete(key); contents.delete(key); }
export function clearAppGlance(component: string): void {
  for (const [key, source] of sources) if (source.component === component) removeContent(key);
  listeners.forEach(callback => callback());
}
/** Atomically apply the native catalog; removal/revocation also removes private frames. */
export function applyAppGlanceRegistry(value: any): void {
  if (value?.version !== 1 || !Array.isArray(value.providers) || value.providers.length > 64) return;
  const next = new Map<string, AppGlanceSource>();
  for (const provider of value.providers) {
    if (typeof provider.component !== 'string' || !Array.isArray(provider.widgets) || provider.widgets.length > 8) continue;
    for (const widget of provider.widgets) {
      if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/.test(widget.id) || ![1,2].includes(widget.rows) || !['list','scene'].includes(widget.kind)) continue;
      const key = appGlanceKey(provider.component, widget.id);
      next.set(key, { ...widget, key, component: provider.component, connected: provider.connected === true, title: widget.label });
    }
  }
  if (JSON.stringify([...next]) === JSON.stringify([...sources])) return;
  for (const [key, source] of sources) {
    const replacement = next.get(key);
    if (!replacement?.connected || JSON.stringify(source) !== JSON.stringify(replacement)) removeContent(key);
  }
  sources.clear(); for (const [key, source] of next) sources.set(key, source);
  registryListeners.forEach(callback => callback());
  listeners.forEach(callback => callback());
}
export function acceptAppGlance(component: string, value: AppGlanceContent): void {
  const key = appGlanceKey(component, value?.widgetId ?? 'default'), source = sources.get(key);
  if (!source?.connected || ![1,2].includes(value?.version) ||
      !Number.isSafeInteger(value.expiresAt) || value.expiresAt <= Date.now() || value.expiresAt > Date.now() + 60000) return;
  if (value.version === 1 && source.kind !== 'list') return;
  contents.set(key, value); clearTimeout(expiryTimers.get(key));
  expiryTimers.set(key, setTimeout(() => { removeContent(key); listeners.forEach(callback => callback()); }, Math.max(1, value.expiresAt - Date.now())));
  listeners.forEach(callback => callback());
}
export function appGlanceContent(key: string, now = Date.now()): AppGlanceContent | null {
  const value = contents.get(key);
  if (!value || value.expiresAt <= now) return null;
  return { ...value, ...(value.entries ? { entries: value.entries.filter(row => row.expiresAt > now) } : {}) };
}
