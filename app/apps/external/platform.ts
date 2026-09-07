import { Application, Utils } from "@nativescript/core";
import { shell, type ShellWindow } from "../../ui/shell/shell";
import { windowIcon } from "../../ui/shell/chrome-layer";
import { appViewportSize, type WindowHeightMode } from "../../ui/shell/geometry";
import { publishExternalNotificationPosted } from "../../native/notification-icons";
import { acceptExternalNotificationReplyResult, clearExternalNotifications, configureExternalNotifications, configureExternalNotificationReplies, putExternalNotification, removeExternalNotification, setSuppressedNotificationPackages } from "../../native/external-notifications";
import * as frameTimings from "../../native/frame-timings";

declare const com: any;
export type InstalledApk = { component: string; name: string; connected: boolean };
export const externalAppId = (component: string): string => `apk:${component}`;
function manager(): any { return global.isAndroid ? com.faceclaw.app.FaceclawExternalApps.get(Utils.android.getApplicationContext()) : null; }
export function installedExternalApps(): InstalledApk[] { try { return JSON.parse(String(manager()?.installedJson() ?? "[]")); } catch { return []; } }
export function showExternalAppSettings(): void { manager()?.showManager(Application.android.foregroundActivity ?? Application.android.startActivity); }

type Raster = { width: number; height: number; pixels: Uint8Array };
type WindowState = { window: ShellWindow; ready: boolean; serial: number; visible: boolean; frame: Raster | null; rendering: boolean; target: string; lastInput: number; cancelReview?: () => void; reviewId?: string; reviewPurpose?: "message" | "search" };
export type ExternalPlatformOptions = {
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
  constructor(private readonly options: ExternalPlatformOptions) {
    configureExternalNotifications((component, target) => { void this.open(component, target); }, publishExternalNotificationPosted);
    configureExternalNotificationReplies(
      component => !this.options.isLocked() && shell.isScreenOn() && this.granted(component, "notifications") && this.granted(component, "dictation"),
      (component, data) => Boolean(this.native?.replyToNotification(component, JSON.stringify(data))),
    );
    this.listener = global.isAndroid ? new com.faceclaw.app.FaceclawExternalAppListener({
      onEvent: (component: string, type: string, json: string) => this.onEvent(String(component), String(type), JSON.parse(String(json))),
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
    setInterval(() => publishExternalNotificationPosted(""), 1000);
  }
  private send(component: string, type: string, data: unknown = {}): void { this.native?.send(component, type, JSON.stringify(data)); }
  private granted(component: string, capability: string): boolean { return Boolean(this.native?.allows(component, capability)); }
  async open(component: string, target = ""): Promise<void> {
    const app = installedExternalApps().find((item) => item.component === component);
    if (!app?.connected) { showExternalAppSettings(); return; }
    const previous = this.windows.get(component);
    if (previous) {
      previous.cancelReview?.(); previous.target = target;
      this.send(component, "open", { ...appViewportSize(previous.window.heightMode ?? "min"), target });
      shell.focusWindow(previous.window.windowId); this.options.requestRender(); return;
    }
    const id = externalAppId(component), surfaceId = `window:${id}`;
    const state: WindowState = { window: null!, ready: false, serial: 0, visible: false, frame: null, rendering: false, target, lastInput: 0 };
    const window: ShellWindow = {
      appId: id, windowId: id, title: app.name, surfaceId, closeable: true, heightMode: "min", drawIcon: windowIcon("package", app.name.slice(0, 1)),
      close: () => { state.ready = false; state.frame = null; state.cancelReview?.(); this.send(component, "close"); this.windows.delete(component); this.options.removeSurface(surfaceId); },
      handleInput: (event, frameId) => {
        if (event.type === "short-then-long-press") { shell.openSystemMenu(id); return; }
        if (event.type === "system-menu-opened") return;
        state.lastInput = Date.now(); this.send(component, "input", event); frameTimings.finishFrame(frameId, "external app input dispatched");
      },
      requestRender: () => this.send(component, "render"),
      relayout: () => { state.ready = false; state.cancelReview?.(); state.frame = null; void this.options.configureSurface(surfaceId, state.visible, window.heightMode ?? "min").then(() => { if (this.windows.get(component) !== state) return; state.ready = true; this.send(component, "resize", appViewportSize(window.heightMode ?? "min")); }); },
      setForeground: (visible) => { state.visible = visible; if (!visible) { state.frame = null; state.cancelReview?.(); } this.options.setSurfaceVisible(surfaceId, visible); this.send(component, "visibility", { visible, screenOn: shell.isScreenOn() && !this.options.isLocked() }); },
      setScreenOn: (on) => { if (!on) { state.frame = null; state.cancelReview?.(); } this.send(component, "visibility", { visible: state.visible, screenOn: on && !this.options.isLocked() }); },
    };
    state.window = window; this.windows.set(component, state); shell.registerWindow(window);
    await this.options.configureSurface(surfaceId, false, "min");
    if (this.windows.get(component) !== state) return;
    state.ready = true; this.send(component, "open", { ...appViewportSize("min"), target }); shell.focusWindow(id); this.options.requestRender();
  }
  lockChanged(): void {
    for (const [component, state] of this.windows) {
      state.cancelReview?.(); state.frame = null;
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
    const state = this.windows.get(component);
    if (type === "disconnected") { state?.cancelReview?.(); if (state) shell.closeWindow(state.window.windowId); clearExternalNotifications(component); }
    if (["connected", "disconnected", "changed", "grants-changed"].includes(type)) {
      if (type === "grants-changed") { state?.cancelReview?.(); clearExternalNotifications(component); }
      setSuppressedNotificationPackages(JSON.parse(String(this.native.suppressedPackagesJson())));
      this.options.requestRender(); publishExternalNotificationPosted(""); return;
    }
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
