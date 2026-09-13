import { boundedToken, record } from "./extension-policy";
declare function setTimeout(callback: () => void, delay: number): number;
declare function clearTimeout(handle: number): void;
type ToolResult = { ok: boolean; content?: string; error?: string };
type CapabilityCaller = { participant: string; origin?: "bridge" | "host"; project?: string; session?: string };

export type AppCapability = {
  id: string; version: number; kind: "operation" | "interface"; title: string; description: string;
  inputSchema: Record<string, unknown>; resultVisibility: "agent" | "status-only" | "local";
  durability: "transient" | "reconnect" | "durable"; operationClass: "read" | "control" | "side-effect";
  profiles: { id: string; version: number }[];
};
type Pending = { component: string; generation: number; capability: AppCapability; resolve: (value: ToolResult) => void; timer: ReturnType<typeof setTimeout> };
const capabilityId = (value: unknown): value is string => typeof value === "string" && value.length <= 128 && /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+){2,}$/.test(value);
const participantId = (value: unknown): value is string => typeof value === "string" && value.length > 0 && value.length <= 512 && /^[A-Za-z0-9_.$:/-]+$/.test(value);
const states = new Set(["running", "waiting_for_user", "waiting_for_presentation", "completed", "failed", "cancelled", "unknown"]);
const argumentText = (value: string): boolean => !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\u202a-\u202e\u2066-\u2069]/u.test(value);
function validValue(schema: unknown, value: unknown, depth = 0, budget = { nodes: 0 }): boolean {
  if (!record(schema) || depth > 6 || ++budget.nodes > 256 || typeof schema.type !== "string") return false;
  let valid = false;
  if (schema.type === "object") {
    const properties = schema.properties;
    if (!record(value) || !record(properties) || schema.additionalProperties !== false || Object.keys(value).length > 64) return false;
    const required = Array.isArray(schema.required) ? schema.required : [];
    if (required.some(name => typeof name !== "string" || !(name in value))) return false;
    valid = Object.entries(value).every(([name, child]) => name in properties && validValue(properties[name], child, depth + 1, budget));
  } else if (schema.type === "array") {
    const minimum = typeof schema.minItems === "number" ? schema.minItems : 0, maximum = typeof schema.maxItems === "number" ? schema.maxItems : 128;
    valid = Array.isArray(value) && value.length >= minimum && value.length <= maximum && value.every(child => validValue(schema.items, child, depth + 1, budget));
  } else if (schema.type === "string") {
    const minimum = typeof schema.minLength === "number" ? schema.minLength : 0, maximum = typeof schema.maxLength === "number" ? schema.maxLength : 16000;
    valid = typeof value === "string" && value.length >= minimum && value.length <= maximum && argumentText(value);
  } else if (schema.type === "boolean") valid = typeof value === "boolean";
  else if (schema.type === "integer") valid = Number.isSafeInteger(value);
  else if (schema.type === "number") valid = typeof value === "number" && Number.isFinite(value);
  if (!valid) return false;
  if ((schema.type === "integer" || schema.type === "number") && typeof value === "number" &&
      (typeof schema.minimum === "number" && value < schema.minimum || typeof schema.maximum === "number" && value > schema.maximum)) return false;
  if (Array.isArray(schema.enum)) {
    try { return schema.enum.some(item => JSON.stringify(item) === JSON.stringify(value)); } catch { return false; }
  }
  return true;
}

/** Routes dynamically published APK capabilities without knowing provider-specific workflows. */
export class AppCapabilityRegistry {
  private readonly catalogs = new Map<string, { generation: number; capabilities: AppCapability[] }>();
  private readonly pending = new Map<string, Pending>();
  private revision = 0;
  constructor(private readonly send: (component: string, type: string, data: unknown) => boolean,
    private readonly changed: () => void, private readonly id: () => string, private readonly now: () => number = Date.now) {}
  update(component: string, value: unknown): boolean {
    if (!record(value) || value.version !== 1 || !Number.isSafeInteger(value.generation) || Number(value.generation) < 1 || !Array.isArray(value.capabilities) || value.capabilities.length > 64) return false;
    const capabilities: AppCapability[] = [];
    const names = new Set<string>();
    for (const item of value.capabilities) {
      if (!record(item) || !capabilityId(item.id) || names.has(item.id) || !Number.isSafeInteger(item.version) || Number(item.version) < 1 ||
        !["operation", "interface"].includes(String(item.kind)) || typeof item.title !== "string" || !item.title || item.title.length > 100 ||
        typeof item.description !== "string" || !item.description || item.description.length > 1000 || !record(item.inputSchema) || item.inputSchema.type !== "object" ||
        !["agent", "status-only", "local"].includes(String(item.resultVisibility)) || !["transient", "reconnect", "durable"].includes(String(item.durability)) ||
        !["read", "control", "side-effect"].includes(String(item.operationClass)) || !Array.isArray(item.profiles) || item.profiles.length > 8) return false;
      const profiles = item.profiles.map(profile => record(profile) && capabilityId(profile.id) && Number.isSafeInteger(profile.version) && Number(profile.version) > 0 ? { id: profile.id, version: Number(profile.version) } : null);
      if (profiles.some(profile => !profile)) return false;
      names.add(item.id); capabilities.push({ ...(item as unknown as AppCapability), profiles: profiles as { id: string; version: number }[] });
    }
    const previous = this.catalogs.get(component);
    if (previous && Number(value.generation) <= previous.generation) return false;
    for (const [requestId, pending] of this.pending) if (pending.component === component && pending.generation !== Number(value.generation)) {
      clearTimeout(pending.timer); this.pending.delete(requestId);
      pending.resolve({ ok: false, error: "Provider catalog changed; operation outcome may be unknown" });
    }
    this.catalogs.set(component, { generation: Number(value.generation), capabilities }); this.revision++; this.changed(); return true;
  }
  remove(component: string): void {
    if (this.catalogs.delete(component)) { this.revision++; this.changed(); }
    for (const [requestId, pending] of this.pending) if (pending.component === component) {
      clearTimeout(pending.timer); this.pending.delete(requestId); pending.resolve({ ok: false, error: "Provider disconnected; operation outcome may be unknown" });
    }
  }
  tools(reserved = new Set<string>()): { name: string; description: string; inputSchema: Record<string, unknown>; _meta: Record<string, unknown> }[] {
    const duplicate = new Set<string>(), owner = new Map<string, string>();
    for (const [component, catalog] of this.catalogs) for (const capability of catalog.capabilities) {
      if (reserved.has(capability.id) || owner.has(capability.id) && owner.get(capability.id) !== component) duplicate.add(capability.id); else owner.set(capability.id, component);
    }
    return [...this.catalogs.entries()].flatMap(([component, catalog]) => catalog.capabilities.filter(capability => !duplicate.has(capability.id)).map(capability => ({
      name: capability.id,
      description: `${capability.description} Provider: ${component}. This provider controls required interaction; never infer completion from dispatch.`,
      inputSchema: capability.inputSchema,
      _meta: { "org.faceclaw/capability": { protocolVersion: 1, provider: component, id: capability.id, version: capability.version,
        kind: capability.kind, resultVisibility: capability.resultVisibility, durability: capability.durability,
        operationClass: capability.operationClass, profiles: capability.profiles } },
    }))).sort((a, b) => a.name.localeCompare(b.name));
  }
  has(name: string, reserved = new Set<string>()): boolean { return this.tools(reserved).some(tool => tool.name === name); }
  async call(caller: string | CapabilityCaller, name: string, args: Record<string, unknown>): Promise<ToolResult> {
    const matches = [...this.catalogs.entries()].flatMap(([component, catalog]) => catalog.capabilities.filter(capability => capability.id === name).map(capability => ({ component, catalog, capability })));
    if (matches.length !== 1) return { ok: false, error: matches.length ? "Capability name is ambiguous" : "Capability unavailable" };
    const { component, catalog, capability } = matches[0];
    let argumentsSize = Number.POSITIVE_INFINITY; try { argumentsSize = JSON.stringify(args).length; } catch { /* Invalid transport value. */ }
    const identity: CapabilityCaller = typeof caller === "string" ? { participant: caller } : caller;
    if (!participantId(identity.participant) || identity.origin !== undefined && !["bridge", "host"].includes(identity.origin) ||
      identity.project !== undefined && !boundedToken(identity.project, 256) || identity.session !== undefined && !boundedToken(identity.session, 256) ||
      !record(args) || argumentsSize > 16384 || !validValue(capability.inputSchema, args) || this.pending.size >= 32) return { ok: false, error: "Invalid capability call" };
    const requestId = this.id(), issuedAt = this.now(), expiresAt = issuedAt + 25000;
    return new Promise(resolve => {
      const timer = setTimeout(() => {
        if (!this.pending.delete(requestId)) return;
        this.send(component, "capability-cancel", { requestId });
        resolve({ ok: false, error: capability.operationClass === "read" ? "Capability timed out" : "Capability result is unknown; do not retry" });
      }, 25000);
      this.pending.set(requestId, { component, generation: catalog.generation, capability, resolve, timer });
      const sent = this.send(component, "capability-request", { requestId, capabilityId: capability.id, capabilityVersion: capability.version,
        catalogGeneration: catalog.generation, arguments: args, caller: identity, issuedAt, expiresAt });
      if (!sent) { clearTimeout(timer); this.pending.delete(requestId); resolve({ ok: false, error: "Capability provider unavailable" }); }
    });
  }
  event(component: string, type: string, value: unknown): boolean {
    if (type === "capabilities-changed") return this.update(component, value);
    if (type === "disconnected" || type === "changed") { this.remove(component); return false; }
    if (type !== "capability-result" && type !== "capability-progress") return false;
    if (!record(value) || !boundedToken(value.requestId) || !record(value.result)) return true;
    const pending = this.pending.get(value.requestId); if (!pending || pending.component !== component) return true;
    const state = value.result.state, operationId = value.result.operationId, message = value.result.message;
    if (typeof state !== "string" || !states.has(state) || operationId !== undefined && !boundedToken(operationId) || message !== undefined && (typeof message !== "string" || message.length > 1000)) return true;
    if (type === "capability-progress") return true;
    clearTimeout(pending.timer); this.pending.delete(value.requestId);
    const visible: Record<string, unknown> = { state };
    if (operationId !== undefined) visible.operationId = operationId;
    if (message !== undefined) visible.message = message;
    if (Array.isArray(value.result.continuations)) visible.continuations = value.result.continuations;
    if (pending.capability.resultVisibility === "agent" && value.result.content !== undefined) visible.content = value.result.content;
    const failed = ["failed", "cancelled", "unknown"].includes(state);
    pending.resolve(failed ? { ok: false, error: JSON.stringify(visible) } : { ok: true, content: JSON.stringify(visible) });
    return true;
  }
  get catalogRevision(): number { return this.revision; }
}
