import { ExtensionPlatform, type ExtensionHooks } from "./extension-platform";
import { boundedToken } from "./extension-policy";
import { Application, Utils } from "@nativescript/core";
import { shell, type ShellWindow } from "../../ui/shell/shell";
import { windowIcon } from "../../ui/shell/chrome-layer";
import { appViewportSize, type WindowHeightMode } from "../../ui/shell/geometry";
import { publishExternalNotificationPosted } from "../../native/notification-icons";
import { acceptExternalNotificationReplyResult, clearExternalNotifications, configureExternalNotifications, configureExternalNotificationReplies, expireExternalNotifications, putExternalNotification, removeExternalNotification, setSuppressedNotificationPackages } from "../../native/external-notifications";
import { refineHostDictation } from "../../native/anthropic";
import { anthropicApiKeySetting } from "../../ui/dashboard-settings";
import * as frameTimings from "../../native/frame-timings";
import type { IconName } from "../../graphics/icons";

declare const com: any;
export type InstalledApk = { component: string; name: string; connected: boolean };
export const externalAppId = (component: string): string => `apk:${component}`;
export const externalAppIcon = (component: string): IconName => {
  const separator = component.indexOf("/");
  const packageName = (separator >= 0 ? component.slice(0, separator) : component).trim();
  if (packageName === "com.faceclaw.t3") return "t3";
  if (packageName === "com.faceclaw.signal") return "message";
  return "package";
};
function manager(): any { return global.isAndroid ? com.faceclaw.app.FaceclawExternalApps.get(Utils.android.getApplicationContext()) : null; }
export function installedExternalApps(): InstalledApk[] { try { return JSON.parse(String(manager()?.installedJson() ?? "[]")); } catch { return []; } }
export function showExternalAppSettings(): void { manager()?.showManager(Application.android.foregroundActivity ?? Application.android.startActivity); }
export type InstalledAndroidApp = { packageName: string; name: string };
export function installedAndroidApps(): InstalledAndroidApp[] { try { return JSON.parse(String(manager()?.androidAppsJson() ?? "[]")); } catch { return []; } }
export function openAndroidAppSettings(packageName: string): boolean { return Boolean(manager()?.openAndroidAppSettings(Application.android.foregroundActivity ?? Application.android.startActivity, packageName)); }
export function prioritizeExtension(feature: string, component: string): boolean { return Boolean(manager()?.prioritizeExtension(feature, component)); }


type Raster = { width: number; height: number; pixels: Uint8Array };
type LocalWindowPolicy = { preferredHeightMode: WindowHeightMode; preferredWidthMode: "display"; chrome: "host" | "compact"; menuAvailable: boolean; back: "app-then-host" | "host-only"; gestureClaims: string[] };
type WindowState = { contractCapture?: { id: string; expires: number; timer?: ReturnType<typeof setTimeout> }; policy?: LocalWindowPolicy; claimsActive?: boolean; backPending?: { expires: number; timer: ReturnType<typeof setTimeout> }; window: ShellWindow; ready: boolean; serial: number; visible: boolean; frame: Raster | null; rendering: boolean; target: string; lastInput: number; cancelReview?: () => void; reviewId?: string; reviewPurpose?: "message" | "search" | "capture" | "composer"; protected?: boolean; menuAvailable?: boolean; finishCapture?: () => void; completedCapture?: { text: string; at: number }; refinement?: { id: string; cancel: () => void } };
export type ExternalPlatformOptions = {
  extensions?: ExtensionHooks;
  configureSurface: (id: string, visible: boolean, mode: WindowHeightMode) => Promise<void>;
  setSurfaceVisible: (id: string, visible: boolean) => void;
  removeSurface: (id: string) => void;
  submitRaster: (id: string, pixels: Uint8Array, width: number, height: number, serial: number) => Promise<void>;
  requestRender: () => void;
  isLocked: () => boolean;
};
/** Shell adapter contains no Signal account, bridge credential, or foreign code. */
export class ExternalAppPlatform {
  private readonly native = manager();
  private readonly windows = new Map<string, WindowState>();
  private readonly listener: any;
  readonly extensions: ExtensionPlatform;
  private readonly captureHistory = new Map<string, Map<string, { body: string; at: number; result?: any }>>();
  private readonly hostStateSnapshots = new Map<string, string>();
  constructor(private readonly options: ExternalPlatformOptions) {
    this.extensions = new ExtensionPlatform(this.native, { ...options.extensions, openAssistant: async (component, current) => {
      if (!current()) return false;
      await this.open(component, "", current);
      const state = this.windows.get(component);
      if (!current() || !state?.ready || !state.visible) return false;
      state.lastInput = Date.now();
      return true;
    } }, options.isLocked, () => {
      const id = shell.foregroundWindow()?.windowId;
      return [...this.windows.values()].some(state => state.window.windowId === id && (state.protected || !!state.reviewId || !!state.refinement));
    });
    configureExternalNotifications((component, target) => { void this.open(component, target); }, publishExternalNotificationPosted);
    configureExternalNotificationReplies(
      component => !this.options.isLocked() && shell.isScreenOn() && this.granted(component, "notifications") && this.granted(component, "dictation"),
      (component, data) => Boolean(this.native?.replyToNotification(component, JSON.stringify(data))),
    );
    this.listener = global.isAndroid ? new com.faceclaw.app.FaceclawExternalAppListener({
      onEvent: (component: string, type: string, json: string) => this.onEvent(String(component), String(type), JSON.parse(String(json))),
      onExtensionFrame: (component: string, feature: string, generation: number, width: number, height: number, pixels: any) => {
        this.extensions.onFrame(String(component), String(feature), Number(generation), width, height, new Uint8Array((ArrayBuffer as any).from(pixels)));
      },
      onFrame: (component: string, width: number, height: number, pixels: any) => {
        const state = this.windows.get(String(component));
        if (!state?.ready || !state.visible || this.options.isLocked()) return;
        // Native owns an immutable private byte snapshot, never app shared memory.
        state.frame = { width, height, pixels: new Uint8Array((ArrayBuffer as any).from(pixels)) };
        void this.render(String(component), state);
      },
    }) : null;
    this.native?.setListener(this.listener);
    // Expiry must retract popups even when the bridge is unreachable.
    setInterval(() => {
      if (expireExternalNotifications()) publishExternalNotificationPosted("");
      for (const [component, window] of this.windows) if (window.ready && window.visible && !this.options.isLocked() && shell.isScreenOn()) this.extensions.publishOwnNotifications(component);
      if (!this.options.isLocked()) for (const app of installedExternalApps()) if (app.connected) this.publishHostState(app.component);
    }, 1000);
  }
  private send(component: string, type: string, data: any = {}): void {
    const state = this.windows.get(component), capture = state?.contractCapture;
    if (capture && data.requestId === capture.id && type.startsWith("capture-dictation-")) {
      const next: any = { ...data, captureId: capture.id }; delete next.requestId;
      if (type === "capture-dictation-closed") {
        next.status = data.reason === "complete" ? "complete" : data.reason === "capture-unavailable" ? "rejected" : "cancelled";
        type = "capture-status"; clearTimeout(capture.timer); state!.contractCapture = undefined;
      } else type = type === "capture-dictation-transcript" ? "capture-transcript" : "capture-status";
      const history = this.captureHistory.get(component)?.get(capture.id); if (history && type === "capture-status") history.result = next;
      this.native?.send(component, type, JSON.stringify(next)); return;
    }
    this.native?.send(component, type, JSON.stringify(data));
  }
  private publishHostState(component: string): void {
    const data = this.extensions.ownHostState(component), serialized = JSON.stringify(data);
    if (serialized === this.hostStateSnapshots.get(component)) return;
    this.hostStateSnapshots.set(component, serialized);
    this.send(component, "host-state", data);
  }
  private granted(component: string, capability: string): boolean { return Boolean(this.native?.allows(component, capability)); }
  private heightMode(component: string): WindowHeightMode {
    const local = this.windows.get(component)?.policy; if (local) return local.preferredHeightMode;
    const layout = this.extensions.feature("ui.window-layout");
    return layout?.component === component && ["min", "medium", "max"].includes(String(layout.configuration.ownHeightMode)) ? layout.configuration.ownHeightMode as WindowHeightMode : "min";
  }
  async open(component: string, target = "", current: () => boolean = () => true): Promise<void> {
    if (!current()) return;
    const app = installedExternalApps().find((item) => item.component === component);
    if (!app?.connected) { showExternalAppSettings(); return; }
    const previous = this.windows.get(component);
    if (previous) {
      this.cancelOwnedWork(previous); previous.target = target;
      this.send(component, "open", { ...appViewportSize(previous.window.heightMode ?? "min"), target });
      shell.focusWindow(previous.window.windowId); this.options.requestRender(); return;
    }
    const id = externalAppId(component), surfaceId = `window:${id}`, heightMode = this.heightMode(component);
    const state: WindowState = { window: null!, ready: false, serial: 0, visible: false, frame: null, rendering: false, target, lastInput: 0 };
    const window: ShellWindow = {
      appId: id, windowId: id, title: app.name, surfaceId, closeable: true, heightMode, drawIcon: windowIcon(externalAppIcon(component), app.name.slice(0, 1)),
      close: () => { state.ready = false; state.frame = null; this.cancelOwnedWork(state); this.hostStateSnapshots.delete(component); this.send(component, "close"); this.windows.delete(component); this.options.removeSurface(surfaceId); },
      hasAppMenu: () => state.menuAvailable === true,
      claimsLongPress: () => state.claimsActive === true && state.policy?.gestureClaims.includes("long-press") === true,
      handleInput: (event, frameId) => {
        if (state.visible) this.extensions.windowInput(component, event);
        if (state.policy && event.type === "double-click") {
          if (state.backPending) { frameTimings.finishFrame(frameId, "back pending"); return; }
          if (state.policy.back === "host-only") { shell.returnFromAppRoot(); frameTimings.finishFrame(frameId, "host root back"); return; }
          const pending = { expires: Date.now() + 500, timer: null! as ReturnType<typeof setTimeout> };
          state.backPending = pending;
          pending.timer = setTimeout(() => { if (state.backPending !== pending) return; state.backPending = undefined; if (state.ready && state.visible && !this.options.isLocked() && shell.isScreenOn() && !state.protected && !state.reviewId && !state.refinement) shell.returnFromAppRoot(); }, 500);
        }
        if (event.type === "short-then-long-press") {
          if (!state.menuAvailable) {
            shell.openSystemMenu(id);
            frameTimings.finishFrame(frameId, "external app system menu opened");
            return;
          }
          state.lastInput = Date.now(); this.send(component, "app-menu"); frameTimings.finishFrame(frameId, "external app menu dispatched"); return;
        }
        if (event.type === "system-menu-opened") {
          frameTimings.finishFrame(frameId, "external app yielded to system menu");
          return;
        }
        state.lastInput = Date.now(); this.send(component, "input", event); frameTimings.finishFrame(frameId, "external app input dispatched");
      },
      requestRender: () => this.send(component, "render"),
      relayout: () => { window.heightMode = this.heightMode(component); state.ready = false; this.cancelOwnedWork(state); state.frame = null; void this.options.configureSurface(surfaceId, state.visible, window.heightMode ?? "min").then(() => { if (this.windows.get(component) !== state) return; state.ready = true; this.send(component, "resize", appViewportSize(window.heightMode ?? "min")); }); },
      setForeground: (visible) => { state.visible = visible; if (!visible) { state.frame = null; this.cancelOwnedWork(state); } this.options.setSurfaceVisible(surfaceId, visible); this.send(component, "visibility", { visible, screenOn: shell.isScreenOn() && !this.options.isLocked() }); },
      setScreenOn: (on) => { if (!on) { state.frame = null; this.cancelOwnedWork(state); } this.send(component, "visibility", { visible: state.visible, screenOn: on && !this.options.isLocked() }); },
    };
    state.window = window; this.windows.set(component, state); shell.registerWindow(window);
    await this.options.configureSurface(surfaceId, false, heightMode);
    if (this.windows.get(component) !== state) return;
    if (!current()) { shell.closeWindow(id); return; }
    state.ready = true; this.send(component, "open", { ...appViewportSize(heightMode), target }); shell.focusWindow(id); this.options.requestRender();
  }
  lockChanged(): void {
    this.extensions.lockChanged();
    this.hostStateSnapshots.clear();
    for (const [component, state] of this.windows) {
      this.cancelOwnedWork(state); state.frame = null;
      this.send(component, "visibility", { visible: state.visible, screenOn: shell.isScreenOn() && !this.options.isLocked() });
    }
  }
  private async render(component: string, state: WindowState): Promise<void> {
    if (state.rendering) return;
    state.rendering = true;
    try {
      while (state.frame && state.ready && state.visible && this.windows.get(component) === state) {
        const frame = state.frame; state.frame = null;
        await this.options.submitRaster(state.window.surfaceId!, frame.pixels, frame.width, frame.height, ++state.serial);
      }
    } finally { state.rendering = false; }
  }
  private onEvent(component: string, type: string, data: any): void {
    if (["connected", "disconnected", "changed", "grants-changed", "extensions-changed"].includes(type)) this.hostStateSnapshots.delete(component);
    if (this.extensions.onNativeEvent(component, type, data)) {
      if (type === "extensions-changed") for (const [owner, state] of this.windows) if (state.window.heightMode !== this.heightMode(owner)) state.window.relayout?.();
      return;
    }
    const state = this.windows.get(component);
    if (type === "contract-invalidated") { if (state) { this.cancelOwnedWork(state); state.policy = undefined; state.window.compactChrome = false; } return; }
    if (type === "capture-start") {
      const id = data.captureId, body = JSON.stringify(data);
      let history = this.captureHistory.get(component); if (!history) { history = new Map(); this.captureHistory.set(component, history); }
      for (const [key, value] of history) if (Date.now() - value.at >= 300000 && state?.contractCapture?.id !== key) history.delete(key);
      const old = history.get(id);
      if (old) { if (old.body === body && old.result) this.send(component, "capture-status", old.result); return; }
      if (!state || state.contractCapture || history.size >= 64) { this.send(component, "capture-status", { captureId: id, status: "rejected", reason: "busy" }); return; }
      history.set(id, { body, at: Date.now() });
      const capture = { id, expires: Number(data.expiresAtElapsedMs), timer: undefined as ReturnType<typeof setTimeout> | undefined }; state.contractCapture = capture;
      this.startCapture(component, { requestId: id, label: data.label }, state);
      if (state.contractCapture === capture) capture.timer = setTimeout(() => { if (state.contractCapture === capture) state.cancelReview?.(); }, Math.max(0, Math.min(300000, capture.expires - Number(android.os.SystemClock.elapsedRealtime()))));
      return;
    }
    if (type === "composer-start") {
      const id = typeof data.composerId === "string" ? data.composerId.slice(0, 128) : "";
      const reject = (reason: string) => this.send(component, "composer-status", { composerId: id, status: "rejected", reason });
      if (!state?.ready || !state.visible || this.options.isLocked() || !shell.isScreenOn() || !this.granted(component, "dictation") ||
          !boundedToken(data.composerId) || !["generic", "message"].includes(data.purpose) || typeof data.target !== "string" || !data.target || data.target.length > 512 ||
          typeof data.label !== "string" || !data.label || data.label.length > 100 || typeof data.initialText !== "string" || !Number.isSafeInteger(data.maxText) ||
          data.maxText < 1 || data.maxText > 20000 || data.initialText.length > data.maxText || Date.now() - state.lastInput > 5000 || state.cancelReview || state.refinement) {
        reject("composer_unavailable"); return;
      }
      state.lastInput = 0; state.reviewId = id; state.reviewPurpose = "composer";
      let settled = false;
      const clear = () => { if (state.reviewId === id && state.reviewPurpose === "composer") { state.cancelReview = undefined; state.reviewId = undefined; state.reviewPurpose = undefined; } };
      const cancel = this.extensions.startComposer({ caller: component, id, purpose: data.purpose, target: data.target, label: data.label,
        initialText: data.initialText, maxText: data.maxText, originWindowId: state.window.windowId,
        complete: (status, text, reason) => {
          if (settled) return; settled = true; clear();
          this.send(component, "composer-status", { composerId: id, status, ...(text === undefined ? {} : { text }), ...(reason ? { reason } : {}) });
        } });
      if (!cancel) {
        if (!settled) { clear(); reject("composer_provider_unavailable"); }
        return;
      }
      state.cancelReview = () => { cancel(); if (!settled) { settled = true; clear(); this.send(component, "composer-status", { composerId: id, status: "cancelled", reason: "lifecycle" }); } };
      return;
    }
    if (type === "composer-cancel") {
      if (state?.reviewPurpose === "composer" && state.reviewId === data.composerId) state.cancelReview?.();
      else this.extensions.cancelComposer(component, data.composerId);
      return;
    }
    if (type === "capture-finish" || type === "capture-cancel") { if (state?.contractCapture?.id === data.captureId) { if (type === "capture-finish") state.finishCapture?.(); else state.cancelReview?.(); } return; }
    if (type === "contract-control") { void this.applyContractControl(component, data); return; }
    if (type === "recovering") {
      this.captureHistory.delete(component);
      if (state) { state.ready = false; state.frame = null; this.cancelOwnedWork(state); }
      this.options.requestRender(); return;
    }
    if (type === "connected" && state) {
      const size = appViewportSize(state.window.heightMode ?? "min");
      state.ready = true;
      this.send(component, "open", { ...size, target: state.target });
      this.send(component, "visibility", { visible: state.visible, screenOn: shell.isScreenOn() && !this.options.isLocked() });
    }
    if (type === "disconnected") { this.captureHistory.delete(component); if (state) this.cancelOwnedWork(state); if (state) shell.closeWindow(state.window.windowId); clearExternalNotifications(component); }
    if (["connected", "disconnected", "changed", "grants-changed"].includes(type)) {
      if (type === "grants-changed") { if (state) this.cancelOwnedWork(state); clearExternalNotifications(component); }
      setSuppressedNotificationPackages(JSON.parse(String(this.native.suppressedPackagesJson())));
      this.options.requestRender(); publishExternalNotificationPosted(""); return;
    }
    if (type === "own-notifications" || type === "own-notification-action") {
      if (!state?.ready || !state.visible || this.options.isLocked() || !shell.isScreenOn() || !this.native.isExtensionGranted(component, "notification-content")) return;
      if (type === "own-notifications") { this.extensions.publishOwnNotifications(component); return; }
      if (!boundedToken(data.callId) || Date.now() - state.lastInput > 5000 || state.reviewId || state.refinement || !shell.canShowExtensionOverlay()) return;
      state.lastInput = 0;
      const result = this.extensions.actOnOwnNotification(component, data);
      this.send(component, "own-notification-action-result", { callId: data.callId, ...result }); return;
    }
    if (type === "request-open-window") {
      if (this.options.isLocked() || !shell.isScreenOn() || !shell.canShowExtensionOverlay() || typeof data.target !== "string" || data.target.length > 512 || [...this.windows.values()].some(item => item.visible && (item.protected || item.reviewId || item.refinement))) return;
      if (state) this.cancelOwnedWork(state);
      void this.open(component, data.target); return;
    }
    if (type === "window-menu-state") { if (state && typeof data.available === "boolean") state.menuAvailable = data.available; return; }
    if (type === "window-protection") { if (state && typeof data.protected === "boolean") state.protected = data.protected; return; }
    if (type === "request-system-menu") {
      if (!state?.ready || !state.visible || shell.foregroundWindow()?.windowId !== state.window.windowId || this.options.isLocked() || !shell.isScreenOn() || state.protected || state.reviewId || state.cancelReview || state.refinement || !shell.canShowExtensionOverlay()) return;
      const elapsed = Date.now() - state.lastInput;
      if (state.lastInput <= 0 || elapsed < 0 || elapsed > 5000) return;
      state.lastInput = 0;
      shell.openSystemMenu(state.window.windowId); return;
    }
    if (type === "sleep") { if (state?.visible && shell.isScreenOn() && !this.options.isLocked() && Date.now() - state.lastInput <= 5000) { state.lastInput = 0; shell.sleepAtAppRoot(); } return; }
    if (type === "host-refinement") { this.startHostRefinement(component, data, state); return; }
    if (type === "cancel-host-refinement") { if (state?.refinement?.id === data.requestId) state.refinement.cancel(); return; }
    if (type === "capture-dictation") { this.startCapture(component, data, state); return; }
    if (type === "finish-capture-dictation") { if (state?.reviewPurpose === "capture" && state.reviewId === data.requestId) state.finishCapture?.(); return; }
    if (type === "cancel-capture-dictation") { if (state?.reviewPurpose === "capture" && state.reviewId === data.requestId) state.cancelReview?.(); return; }
    if (type === "notification-reply-result") { acceptExternalNotificationReplyResult(component, data); return; }
    if (type === "notification") {
      const app = installedExternalApps().find((item) => item.component === component);
      if (!app || !this.granted(component, "notifications")) return;
      const key = putExternalNotification(component, app.name, data, this.granted(component, "previews"));
      if (key) publishExternalNotificationPosted(key); return;
    }
    if (type === "remove-notification") { if (typeof data.id === "string") removeExternalNotification(component, data.id); return; }
    if (type === "cancel-search-dictation") { if (state?.reviewPurpose === "search" && data.requestId === state.reviewId) state.cancelReview?.(); return; }
    if (type === "search-dictation") { this.startSearch(component, data, state); return; }
    if (type === "cancel-dictation") { if (state?.reviewPurpose === "message" && data.requestId === state.reviewId) state.cancelReview?.(); return; }
    if (type !== "dictation") return;
    const reject = (reason: string) => this.send(component, "dictation-rejected", { requestId: String(data.requestId ?? "").slice(0, 128), reason });
    if (!state?.ready || !state.visible || this.options.isLocked() || !this.granted(component, "dictation")) { reject("App review unavailable"); return; }
    if (typeof data.requestId !== "string" || data.requestId.length > 128 || typeof data.target !== "string" || data.target.length > 512 || typeof data.label !== "string" || data.label.length > 100 || typeof (data.initialText ?? "") !== "string" || (data.initialText?.length ?? 0) > 8000) { reject("Invalid review request"); return; }
    // A recent real window gesture is required; background apps cannot turn on the mic.
    if (Date.now() - state.lastInput > 5000 || state.cancelReview) { reject("Review requires a fresh user gesture"); return; }
    state.lastInput = 0; state.reviewId = data.requestId; state.reviewPurpose = "message";
    const expires = Date.now() + 5 * 60000, requestId = data.requestId, target = data.target;
    let sent = false;
    state.cancelReview = shell.startExternalAppReview(state.window.windowId, data.label, String(data.initialText ?? ""), (text) => {
      if (sent || Date.now() > expires || !this.granted(component, "dictation") || this.windows.get(component) !== state || this.options.isLocked()) return;
      sent = true; this.send(component, "dictation-result", { requestId, target, text, confirmed: true });
    }, () => { if (state.reviewId === requestId && state.reviewPurpose === "message") { state.cancelReview = undefined; state.reviewId = undefined; state.reviewPurpose = undefined; } });
  }
  private async applyContractControl(component: string, request: any): Promise<void> {
    const current = () => Boolean(this.native?.isContractRequestCurrent(component, request.session, request.requestId));
    const finish = (state: string, reason = "") => this.native?.completeContractControl(component, request.session, request.requestId, state, reason);
    const state = this.windows.get(component), op = request.operation, payload = request.payload;
    const visible = () => !!state?.ready && this.windows.get(component) === state && state.visible && shell.foregroundWindow()?.windowId === state.window.windowId && !this.options.isLocked() && shell.isScreenOn();
    const protectedFlow = () => [...this.windows.values()].some(item => item.visible && (item.protected || item.reviewId || item.refinement));
    if (!current()) return;
    if (this.options.isLocked()) { finish("rejected", "locked"); return; }
    if (op === "window.open") {
      const allowed = () => current() && !this.options.isLocked() && shell.isScreenOn() && shell.canShowExtensionOverlay() && !protectedFlow();
      if (!allowed()) { finish("rejected", "protected_flow"); return; }
      try { await this.open(component, payload.target, allowed); finish(allowed() && this.windows.get(component)?.ready ? "applied" : "unknown", allowed() ? "" : "invalid_state"); }
      catch { finish("unknown", "internal_error"); }
      return;
    }
    if (op === "capture.cancel") {
      if (state?.contractCapture?.id !== payload.captureId) { finish("rejected", "invalid_state"); return; }
      state.cancelReview?.(); finish("applied"); return;
    }
    if (!visible()) { finish("rejected", "not_visible"); return; }
    if (op === "window.menu") { state!.menuAvailable = payload.available; finish("applied"); return; }
    if (op === "window.protection") { state!.protected = payload.protected; finish("applied"); return; }
    if (op === "window.policy") {
      if (state!.reviewId || state!.refinement || state!.protected) { finish("rejected", "protected_flow"); return; }
      const prior = state!.policy; state!.policy = payload;
      try {
        if (state!.window.heightMode !== payload.preferredHeightMode) {
          await this.options.configureSurface(state!.window.surfaceId, true, payload.preferredHeightMode);
          if (!current() || !visible()) { state!.policy = prior; finish("unknown", "invalid_state"); return; }
          state!.window.heightMode = payload.preferredHeightMode;
          this.send(component, "resize", appViewportSize(payload.preferredHeightMode));
        }
        state!.menuAvailable = payload.menuAvailable; state!.window.compactChrome = payload.chrome === "compact";
        state!.claimsActive = true; state!.window.acceptsDirectional = payload.gestureClaims.includes("directional");
        this.options.requestRender(); finish("applied");
      } catch { state!.policy = prior; finish("unknown", "internal_error"); }
      return;
    }
    if (op === "window.back") {
      const pending = state!.backPending;
      if (!pending || Date.now() > pending.expires) { finish("rejected", "expired"); return; }
      clearTimeout(pending.timer); state!.backPending = undefined;
      if (payload.response === "at-root") {
        if (protectedFlow()) { finish("rejected", "protected_flow"); return; }
        shell.returnFromAppRoot();
      }
      finish("applied"); return;
    }
    if (op === "window.sleep" || op === "window.system-menu") {
      const age = Date.now() - state!.lastInput;
      if (protectedFlow() || !shell.canShowExtensionOverlay()) { finish("rejected", "protected_flow"); return; }
      if (state!.lastInput <= 0 || age < 0 || age > 5000) { finish("rejected", "not_granted"); return; }
      state!.lastInput = 0;
      if (op === "window.sleep") shell.sleepAtAppRoot(); else shell.openSystemMenu(state!.window.windowId);
      finish("applied"); return;
    }
    finish("rejected", "unsupported");
  }
  private cancelOwnedWork(state: WindowState): void {
    for (const [component, current] of this.windows) if (current === state) { this.extensions.clearOwnNotifications(component); if (state.claimsActive) this.send(component, "input-cancel", { reason: "lifecycle" }); }
    state.claimsActive = false; state.window.acceptsDirectional = false; state.protected = false;
    if (state.backPending) clearTimeout(state.backPending.timer); state.backPending = undefined;
    state.completedCapture = undefined; state.lastInput = 0;
    state.cancelReview?.(); state.refinement?.cancel();
  }
  private startHostRefinement(component: string, data: any, state: WindowState | undefined): void {
    const requestId = typeof data.requestId === "string" ? data.requestId.slice(0, 128) : "";
    const reject = (reason: string) => this.send(component, "host-refinement-rejected", { requestId, reason });
    if (!state?.ready || !state.visible || this.options.isLocked() || !shell.isScreenOn() || !this.granted(component, "dictation") || state.cancelReview || state.refinement || !boundedToken(data.requestId) || typeof data.original !== "string" || data.original.length > 8000 || typeof data.followup !== "string" || !data.followup.trim() || data.followup.length > 8000) { reject("refinement-unavailable"); return; }
    const captured = state.completedCapture;
    const captureAuthorized = captured && Date.now() - captured.at <= 30000 && captured.text === data.followup;
    if (Date.now() - state.lastInput > 5000 && !captureAuthorized) { reject("fresh-user-action-required"); return; }
    // Consume authorization before backend dispatch, including unavailable-key failures.
    state.lastInput = 0; state.completedCapture = undefined;
    const apiKey = anthropicApiKeySetting.get();
    if (!apiKey.trim()) { reject("host-refinement-unavailable"); return; }
    let handle: { cancel: () => void } | undefined, timer: ReturnType<typeof setTimeout> | undefined, finished = false;
    const available = () => this.windows.get(component) === state && state.ready && state.visible && !this.options.isLocked() && shell.isScreenOn() && this.granted(component, "dictation") && state.refinement?.id === requestId;
    const finish = (text?: string) => {
      if (finished) return;
      const allowed = available(); finished = true; if (timer) clearTimeout(timer);
      if (state.refinement?.id === requestId) state.refinement = undefined;
      if (allowed && typeof text === "string" && text.trim() && text.length <= 8000) this.send(component, "host-refinement-result", { requestId, text });
      else reject("refinement-cancelled-or-unavailable");
    };
    const cancel = () => { finish(); handle?.cancel(); };
    state.refinement = { id: requestId, cancel };
    timer = setTimeout(cancel, 120000);
    try { handle = refineHostDictation({ apiKey, original: data.original, followup: data.followup, onDone: text => finish(text), onError: () => finish() }); }
    catch { finish(); }
  }
  private startCapture(component: string, data: any, state: WindowState | undefined): void {
    const requestId = typeof data.requestId === "string" ? data.requestId.slice(0, 128) : "";
    const reject = (reason: string) => this.send(component, "capture-dictation-closed", { requestId, reason });
    if (!state?.ready || !state.visible || this.options.isLocked() || !shell.isScreenOn() || !this.granted(component, "dictation") || !boundedToken(data.requestId) || (data.ownTranscription !== undefined && typeof data.ownTranscription !== "boolean") || typeof data.label !== "string" || data.label.length > 100 || Date.now() - state.lastInput > 5000 || state.cancelReview || state.refinement) { reject("capture-unavailable"); return; }
    if (data.ownTranscription === true && !this.native.isExtensionGranted(component, "transcription")) { reject("transcription-permission-required"); return; }
    state.completedCapture = undefined;
    state.lastInput = 0; state.reviewId = requestId; state.reviewPurpose = "capture";
    let finalText: string | undefined; const contractCapture = state.contractCapture;
    const available = () => (!contractCapture || state.contractCapture === contractCapture) && this.windows.get(component) === state && state.ready && state.visible && !this.options.isLocked() && shell.isScreenOn() && this.granted(component, "dictation") && state.reviewId === requestId && (data.ownTranscription !== true || this.native.isExtensionGranted(component, "transcription"));
    const capture = shell.startExternalAppCapture(state.window.windowId,
      event => { if (available()) { if (event.isFinal && typeof event.text === "string" && event.text.length <= 8000) finalText = event.text; this.send(component, "capture-dictation-transcript", { requestId, ...event, purpose: "capture" }); } },
      status => { if (available()) this.send(component, "capture-dictation-status", { requestId, status }); },
      reason => {
        if (contractCapture && state.contractCapture !== contractCapture) return;
        if (reason === "complete" && finalText !== undefined && available()) state.completedCapture = { text: finalText, at: Date.now() };
        if (state.reviewId === requestId && state.reviewPurpose === "capture") { state.cancelReview = undefined; state.finishCapture = undefined; state.reviewId = undefined; state.reviewPurpose = undefined; }
        reject(reason);
      }, available, data.ownTranscription === true ? { component, captureId: requestId } : undefined);
    if (state.reviewId === requestId) { state.cancelReview = capture.cancel; state.finishCapture = capture.finish; }
  }
  private startSearch(component: string, data: any, state: WindowState | undefined): void {
    const reject = (reason: string) => this.send(component, "search-dictation-rejected", { requestId: String(data.requestId ?? "").slice(0, 128), reason });
    if (!state?.ready || !state.visible || this.options.isLocked() || !shell.isScreenOn() || !this.granted(component, "dictation")) { reject("Voice search unavailable"); return; }
    if (typeof data.requestId !== "string" || !data.requestId || data.requestId.length > 128 || typeof data.target !== "string" || !data.target || data.target.length > 512 || typeof data.label !== "string" || data.label.length > 100) { reject("Invalid search request"); return; }
    const gestureExpires = state.lastInput + 5000;
    if (Date.now() > gestureExpires || state.cancelReview) { reject("Search requires a fresh user gesture"); return; }
    const requestId = data.requestId, target = data.target, expires = Date.now() + 5 * 60000;
    state.lastInput = 0; state.reviewId = requestId; state.reviewPurpose = "search";
    let completed = false, closed = false;
    const available = () => this.windows.get(component) === state && state.ready && state.visible && !this.options.isLocked() && shell.isScreenOn() && this.granted(component, "dictation") && state.reviewId === requestId && state.reviewPurpose === "search";
    state.cancelReview = shell.startExternalAppSearch(state.window.windowId, data.label, query => {
      if (completed || closed || Date.now() > expires || !available() || typeof query !== "string" || !query.trim() || query.length > 256) return;
      completed = true;
      this.send(component, "search-dictation-result", { requestId, target, text: query, confirmed: true });
    }, () => {
      if (closed) return;
      closed = true;
      if (state.reviewId === requestId && state.reviewPurpose === "search") {
        state.cancelReview = undefined; state.reviewId = undefined; state.reviewPurpose = undefined;
        if (!completed) reject("Search cancelled or unavailable");
      }
    }, () => Date.now() <= gestureExpires && available());
  }

}
