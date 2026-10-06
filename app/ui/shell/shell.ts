import { MessagingReviewLayer } from "./messaging-review";
import type { MessagingReview } from "../../assistant/messaging";
import { extensionPlatform } from "../../apps/external/extension-platform";
import { ExtensionLayer } from "./extension-layer";
import { voiceControlBridge } from "../../native/voice-control";
import { encodeShellScene } from "../../graphics/shell-scene";
import { G2_LENS_HEIGHT, G2_LENS_WIDTH, GrayImage } from "../../graphics/image";
import { singlePlane, type Plane } from "../../graphics/plane";
import { getDefaultSmallFont } from "../../graphics/ui-fonts";
import { appMenuPolicy, navigationPolicy, onEffectiveExtensionsChanged, windowLayoutPolicy } from "../extension-settings";
import { appActionItems, presentAppMenu } from "../window-menu";
import { EvenAIStatus, EventSourceType, OsEventTypeList, WatchGestureType } from "../../g2/events";
import { acceptInput } from "../input-monitor";
import type { RawInputEvent } from "../../native/faceclaw-communicator";
import {
  directionalFallback,
  type DirectionalInputEvent,
  GESTURE_SHORT_THEN_LONG_PRESS,
  gestureHints,
  InputEvent,
  type InputEventPayload,
  type InputSource,
  isDirectionalInput,
  isWatchInput,
  makeInputEvent,
} from "../gestures";
import { Layer, LayerActions, LayerContext, LayerStack, noopLayerActions } from "../layers";
import { CONTEXT_MENU_DIM, MenuLayer, type MenuItem } from "../menu";
import { VoiceInputLayer, type VoiceSendTarget } from "./voice-input";
import { VoiceSearchLayer } from "./voice-search";
import { KeyboardInputLayer, type KeyboardInputSession } from "./keyboard-input";
import { voiceActivity } from "./voice-activity";
import { AssistantLayer } from "./assistant";
import { AssistantSession, type AssistantBackendConfig } from "../../assistant/session";
import { resolveAssistantModel, type AssistantModel } from "../../assistant/models";
import { AssistantConversations, type ReasoningLevel } from "../../assistant/conversations";
import { getStringSetting, setStringSetting } from "../../native/settings-store";
import type { AssistantContext } from "../../assistant/types";
import { SingleNotificationLayer } from "../notifications";
import {
  anthropicApiKeySetting,
  assistantBackendSetting,
  assistantBridgeHostSetting,
  assistantBridgePortSetting,
  assistantBridgeTokenSetting,
  assistantModelSetting,
  assistantSkipConfirmationSetting,
  batteryIndicatorSettingsKey,
  brightnessSetting,
  onAnySettingChanged,
  openAiApiKeySetting,
  statusBarPositionSetting,
  statusBarVisibilitySetting,
  timeFormatSetting,
  wakeWordActionSetting,
  windowBorderSetting,
} from "../dashboard-settings";
import { onAmbientCardsChanged } from "./ambient-cards";
import { ShellChromeLayer, sidebarContentSpan, type ShellChromeState, type ShellChromeWindow } from "./chrome-layer";
import { ShellModalLayer } from "./modal-layer";
import { NotificationModalQueue } from "./notification-modal-queue";
import { ALL_NOTIFICATIONS, readActiveNotifications } from "../../native/notification-icons";
import { shouldShowNotificationOnGlasses } from "../../native/notification-sources";
import { ToolDebugMenuLayer } from "./tool-debug-layer";
import type { InProcessWindow } from "./in-process-window";
import { BrightnessPickerLayer } from "./brightness-picker-layer";
import { toolRegistry } from "../../assistant/tool-registry";
import {
  appViewportRect,
  minWindowTop,
  sidebarWidth,
  switcherPosition,
  switcherRowHeight,
  uiDepth,
  windowBandHeight,
  windowFramed,
  windowTop,
  type WindowHeightMode,
} from "./geometry";
import type { AppSwitcherPosition } from "../dashboard-settings";

const currentExtensionPlatform = () => typeof extensionPlatform === "function" ? extensionPlatform() : null;

/**
 * The shell: owns the window registry, focus, screen on/off, and the shell
 * surface (sidebar + top bar + shell overlays such as the escape menu and
 * the voice dialog). Runs on the main thread; windows are hosted in-process
 * or in per-app worker threads.
 *
 * Input flow: every event enters via receiveInput. The shell consumes
 * everything while the sidebar or a shell overlay has focus and forwards the
 * rest to the focused window. Long-press opens the shell-owned system menu
 * (Focus app switcher, Voice input, Brightness, Close window, Debug) without reaching the
 * app, so the shell keeps working when a window's handler hangs; a window
 * that claims long-press for a move of its own gets it forwarded instead, and
 * holding the press past the escape threshold still opens the system menu.
 * The 2.2.9 tap-then-hold gesture goes to the foreground window (from the
 * sidebar it focuses the window first); by convention apps answer it with
 * their own context menu, or ask for the system menu when they have none.
 */

export type ShellWindow = {
  compactChrome?: boolean;
  appId: string;
  windowId: string;
  title: string;
  /** Compositor surface this window renders to; configured at connect / launch. */
  surfaceId: string;
  /** Whether the system menu offers Close app (the launcher is pinned). */
  closeable: boolean;
  /**
   * True when tap-then-hold currently opens the window's own context menu
   * (with at least one entry). While false, the system menu shows no
   * app-menu hint and a tap-then-hold over the open system menu leaves it
   * open instead of switching menus.
   */
  hasAppMenu?: () => boolean;
  /**
   * True while the window gives long-press a meaning of its own (a game
   * move). The shell then forwards long-presses to it instead of opening the
   * system menu; holding the press past the escape threshold still opens it.
   */
  claimsLongPress?: () => boolean;
  /** Chat uses hold-to-talk and tap-then-hold for the system menu, without an escape timer. */
  holdToTalk?: boolean;
  /** A window owns microphone capture outside the shell voice dialog. */
  isVoiceCapturing?: () => boolean;
  /**
   * True while the window shows something the wearer watches without
   * touching anything (a playing video): the idle screen timeout waits, as
   * it does for voice capture, while this window is in the foreground.
   */
  keepsScreenOn?: () => boolean;
  /**
   * True when the window gives swipe-left / swipe-right (watch directional
   * input) a meaning; otherwise the shell forwards directionalFallback(event).
   */
  acceptsDirectional?: boolean;
  /**
   * Window height: the standard 288px band ("min") or the full screen
   * ("max", terminal views). Decides the surface rect and where the shell
   * draws this window's top bar.
   */
  heightMode: WindowHeightMode;
  /** App-side cleanup when the shell closes the window (worker notification, surface removal). */
  close?: () => void;
  drawIcon: ShellChromeWindow["drawIcon"];
  /**
   * Handle an input event the shell forwarded. Ownership of frameId (latency
   * tracking) passes to the window: it must eventually reach a frame submit
   * or a finishFrame call.
   */
  handleInput: (event: InputEvent, frameId: number) => Promise<void> | void;
  /** Repaint and resubmit this window's surface. */
  requestRender: () => void;
  /**
   * Re-measure against the current display mode (viewport size / band) and
   * repaint. Windows without it (workers with a canvas fixed at open) are
   * closed and relaunched by the controller instead.
   */
  relayout?: () => void;
  /**
   * A touch from the phone's mirror at (x, y) in app-viewport coordinates.
   * True if the window acted on it; otherwise the controller sends a select.
   */
  hitTest?: (x: number, y: number) => Promise<boolean> | boolean;
  /**
   * Deliver a text string to the window (e.g. finalized voice input). Optional:
   * only windows that consume typed text (the terminal) implement it.
   */
  receiveTextInput?: (text: string, options?: { submit?: boolean }) => void;
  /** Foreground state changed: this window's surface is (not) the visible one. */
  setForeground?: (foreground: boolean) => void;
  /**
   * Input focus moved into this window. `lastInput` is the most recent input
   * event the shell received — for a focus that a click or swipe caused, the
   * event that caused it. A programmatic focus (a worker's focus-window
   * request, a wake path) can deliver an older event: check timestampMs
   * before treating it as current.
   */
  onFocus?: (lastInput: InputEvent | null) => void;
  /** Screen turned on/off; hidden or screen-off windows should stop painting. */
  setScreenOn?: (on: boolean) => void;
  /**
   * Input focus arrived at or left this window: it is (no longer) where
   * ordinary input goes. Unlike setForeground / onFocus, this also tracks
   * shell overlays (the system menu, a notification modal, the voice dialog)
   * and screen-off, so a game can pause on any of them. Sent on change only.
   */
  setInputFocus?: (focused: boolean) => void;
};

export type ShellConfig = {
  /** Hosts without microphone support hide and reject voice entry points. */
  voiceInputEnabled?: boolean;
  /** Actions handed to shell overlay layers; requestRender must re-render the shell surface. */
  actions: LayerActions;
  getScreenTimeoutMs: () => number | null;
  requestShellRender: () => void;
  /**
   * Asked before the voice dialog opens; resolving false swallows the open.
   * Preview-only mode uses it to turn the tap into a mic-permission prompt
   * when RECORD_AUDIO isn't granted yet (the phone mic is the source there).
   */
  prepareVoiceCapture?: () => Promise<boolean>;
  /**
   * The keyboard dialog opened (with the session the phone types into) or
   * closed (null) by any path; the phone UI shows/hides its typing panel.
   */
  onKeyboardInputChanged?: (session: KeyboardInputSession | null) => void;
  /** Screen on/off changed: the controller blanks/unblanks the compositor. */
  onScreenStateChanged: (on: boolean) => void;
  /** Window registered/removed or foreground changed (persists the open-app list). */
  onWindowsChanged?: () => void;
  /**
   * The Notifications app's window, opened in the background (neither
   * foregrounded nor focused) unless it is open already, for the switcher
   * to show a notification's detail view in; `opened` says this call opened
   * it. Hosts that leave it out offer no notifications to select.
   */
  openNotificationsWindow?: () => { window: InProcessWindow; opened: boolean } | null;
};

/**
 * The app switcher's selection while it is on a notification's icon rather
 * than a window's (bottom switcher only; see Shell.moveSelection). The
 * Notifications app's window is the foreground window meanwhile, with the
 * notification's detail view on top of its stack.
 */
type NotificationSelection = {
  key: string;
  /** Where the notification's icon sat among them, for once it has gone. */
  index: number;
  host: InProcessWindow;
  layer: SingleNotificationLayer;
  /**
   * The Notifications window was opened for the selection: it has no
   * switcher icon of its own, and closes once the selection moves off the
   * notifications.
   */
  transient: boolean;
  /** The window selected before, to go back to when the screen sleeps. */
  returnWindowId: string | null;
};

/** Which surfaces need re-rendering after an input event. */
export type ShellInputOutcome = { shell: boolean; window: boolean };

/**
 * Assistant overlay activity, for mirrors outside the glasses (the watch).
 * "streaming" carries the reply so far (replace semantics); "done" the final
 * reply; "error" the message; "closed" fires when the overlay leaves the
 * stack by any path.
 */
export type AssistantActivityEvent = {
  phase: "thinking" | "streaming" | "done" | "error" | "closed";
  text: string;
};

export type FocusKind = "sidebar" | "window";

const noopActions: LayerActions = noopLayerActions;

/**
 * For a window that claims long-press: hold the press this much past the
 * long-press event (which the firmware itself only fires after a shorter
 * hold) and the shell opens the system menu anyway — the recovery path when
 * the app ignores or mishandles the gesture.
 */
const LONG_PRESS_ESCAPE_MENU_MS = 4000;

/** Only these built-in games may keep an active gameplay hold over hold: app-menu. */
const GAMEPLAY_HOLD_ALLOWLIST = new Set(["blocks", "minesweeper", "pinball"]);

/** Shell-surface overlay menu (the system menu); closing it returns focus to the sidebar. */
class ShellOverlayMenuLayer extends MenuLayer {
  /**
   * Set before popping to keep focus on the window: an entry that acts on
   * the window (Voice input aims its transcript at it) must not first hand
   * focus to the sidebar.
   */
  keepWindowFocus = false;

  constructor(items: MenuItem[], footer: string | undefined, private readonly onClosed: () => void, title = "System") {
    // Aligned to the min-height window band (like the sidebar), wherever the
    // vertical position setting currently puts it; centered over the
    // application area, i.e. the part of the screen beside a side strip
    // (the whole width with a bottom row, or in the full-panel mode, where
    // the strip overlays).
    const width = 272;
    const area = appViewportRect("min");
    super(title, items, {
      x: area.x + (((area.width - width) / 2) | 0),
      y: area.y + 8,
      width,
      minHeight: 150,
      squareCorners: true,
      footer,
      dimUnderneath: CONTEXT_MENU_DIM,
      depth: 4,
    });
  }

  onRemoved(): void {
    if (!this.keepWindowFocus) this.onClosed();
  }
}

/** How long a show_alert popup stays before auto-dismissing. */
const ALERT_DISMISS_MS = 6000;
const ALERT_X = 40;
const ALERT_W = G2_LENS_WIDTH - 80;
const ALERT_Y = 96;
const ALERT_H = 96;

/**
 * A brief text popup on the shell surface (the assistant's show_alert tool and
 * other short notices). Auto-dismisses after a few seconds; a click or
 * double-click dismisses it early.
 */
class ShellAlertLayer implements Layer {
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly text: string, private readonly onDismiss: () => void) {
    this.timer = setTimeout(() => {
      this.timer = null;
      this.onDismiss();
    }, ALERT_DISMISS_MS);
  }

  paint(_ctx: LayerContext, paintBelow: () => GrayImage): GrayImage {
    const image = paintBelow();
    const font = getDefaultSmallFont();
    // Positioned within the min-height window band, like the other shell overlays.
    const alertY = minWindowTop() + ALERT_Y;
    image.fillRect(ALERT_X, alertY, ALERT_W, ALERT_H, 1);
    image.drawRect(ALERT_X, alertY, ALERT_W, ALERT_H, 90);
    image.drawText(font, ALERT_X + 16, alertY + 12, "Assistant", 200);
    image.drawTextWrapped({
      font,
      x: ALERT_X + 16,
      y: alertY + 18 + font.lineHeight + 4,
      width: ALERT_W - 32,
      text: this.text,
      value: 235,
    });
    return image;
  }

  handleInput(event: InputEvent, _ctx: LayerContext): void {
    if (event.type === "click" || event.type === "double-click") {
      this.clearTimer();
      this.onDismiss();
    }
  }

  onRemoved(): void {
    this.clearTimer();
  }

  private clearTimer(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}

/**
 * What a watch swipe means while the app switcher has focus, as the ring
 * gesture it stands for. Swipes are spatial: along the strip they move the
 * selection, toward the app area they enter the selected window, and away
 * from it there is nowhere further to go (ignored). A popup's row of icons
 * lies over the window, so either way across it enters the window.
 */
const SWITCHER_SWIPES: Record<
  AppSwitcherPosition,
  Partial<Record<DirectionalInputEvent["type"], "scroll-up" | "scroll-down" | "click">>
> = {
  left: { "swipe-up": "scroll-up", "swipe-down": "scroll-down", "swipe-right": "click" },
  right: { "swipe-up": "scroll-up", "swipe-down": "scroll-down", "swipe-left": "click" },
  bottom: { "swipe-left": "scroll-up", "swipe-right": "scroll-down", "swipe-up": "click" },
  popup: { "swipe-left": "scroll-up", "swipe-right": "scroll-down", "swipe-up": "click", "swipe-down": "click" },
};

/**
 * Every setting the shell's paint depends on that nothing else repaints it
 * for (the top bar's contents and placement, the window frame, the scene's
 * stereo depth), as one comparable string.
 */
function chromeSettingsKey(): string {
  return `${batteryIndicatorSettingsKey()}|${timeFormatSetting.get()}|${uiDepth()}|${statusBarPositionSetting.get()}`
    + `|${statusBarVisibilitySetting.get()}|${windowBorderSetting.get()}`;
}

class Shell {
  private windows: ShellWindow[] = [];
  private selectedIndex = 0;
  /** Window ids in most-recently-visible-first order; closing the visible window returns to the next entry. */
  private mruWindowIds: string[] = [];
  private focus: FocusKind = "sidebar";
  /** The window last told it holds input focus (see syncInputFocus). */
  private inputFocusedWindowId: string | null = null;
  private screenOn = true;
  private lastInputAtMs = Date.now();
  /** The most recent input event received, for windows gaining focus (see ShellWindow.onFocus). */
  private lastInput: InputEvent | null = null;
  private battery: ShellChromeState["battery"] = {
    headset: null, headsetCharging: null, ring: null, ringCharging: null, watch: null, watchCharging: null,
  };
  private attention = new Map<string, boolean>();
  /** Set while the switcher's selection is on a notification (see NotificationSelection). */
  private selectedNotification: NotificationSelection | null = null;
  // App-provided top-bar tray icons, keyed by owner id; drawn between the
  // notification icons and the battery indicators.
  private readonly trayIcons = new Map<string, GrayImage>();
  private activeVoiceLayer: VoiceInputLayer | VoiceSearchLayer | null = null;
  private activeKeyboardLayer: KeyboardInputLayer | null = null;
  private readonly notificationModals = new NotificationModalQueue<ShellModalLayer>({
    open: (notificationKey) => this.pushNotificationModal(notificationKey),
    isAvailable: (notificationKey) => {
      const notification = readActiveNotifications(ALL_NOTIFICATIONS).find((item) => item.key === notificationKey);
      return !!notification && shouldShowNotificationOnGlasses(notification.packageName);
    },
    sleep: () => this.sleep(),
  });
  private conversations: AssistantConversations | null = null;
  private get assistantSession(): AssistantSession | null {
    return this.conversations?.current().session ?? null;
  }

  getAssistantConversations(): AssistantConversations {
    if (!this.conversations) {
      this.conversations = new AssistantConversations(
        (model, reasoning) => this.resolveAssistantConfiguration(model, reasoning),
        () => assistantModelSetting.get(),
        (value) => setStringSetting("assistant.conversations", value),
        getStringSetting("assistant.conversations", ""),
      );
    }
    return this.conversations;
  }

  async prepareChatVoiceCapture(): Promise<boolean> {
    if (this.activeVoiceLayer || this.activeKeyboardLayer || this.voiceDialogPending) return false;
    const ready = (await this.config.prepareVoiceCapture?.()) ?? true;
    return ready && !this.activeVoiceLayer && !this.activeKeyboardLayer &&
      this.stack.isAtBase() && this.isWindowFocused("ai-chat");
  }
  private assistantLayer: AssistantLayer | null = null;
  private readonly assistantActivityListeners = new Set<(event: AssistantActivityEvent) => void>();
  private readonly alertListeners = new Set<(text: string) => void>();
  private escapeMenuTimer: ReturnType<typeof setTimeout> | null = null;
  /** A hold can be accompanied by a firmware-generated click before release. */
  private appMenuGestureActive = false;
  /** Discrete tap-then-hold has no separate release event to clear its guard. */
  private appMenuOpeningClickGuard = false;
  private appMenuOpeningClickGuardTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly actions: LayerActions = { ...noopActions };
  private config: ShellConfig = {
    actions: noopActions,
    getScreenTimeoutMs: () => null,
    requestShellRender: () => {},
    onScreenStateChanged: () => {},
  };
  private readonly chrome = new ShellChromeLayer(() => this.chromeState());
  private readonly stack = new LayerStack(this.chrome, this.actions);

  // Top-bar settings we mirror into the chrome; a change to any of them
  // repaints the shell surface so the top bar reflects it immediately.
  private chromeSettingsSubscribed = false;
  private lastChromeSettingsKey: string | null = null;

  configure(config: ShellConfig): void {
    // Nearly every state change ends in a shell render request, so it is
    // the natural point to tell windows about input-focus changes too.
    this.config = {
      ...config,
      requestShellRender: () => {
        this.syncInputFocus();
        config.requestShellRender();
      },
    };
    this.stack.setActions(config.actions);
    this.subscribeToChromeSettings();
    this.subscribeToAmbientCards();
  }

  // Ambient (encounter) cards live in the chrome paint; a posted or expired
  // card repaints the shell surface so it appears and disappears on time.
  private ambientCardsSubscribed = false;

  private subscribeToAmbientCards(): void {
    if (this.ambientCardsSubscribed) return;
    this.ambientCardsSubscribed = true;
    onAmbientCardsChanged(() => {
      if (this.screenOn) {
        this.config.requestShellRender();
      }
    });
  }

  private subscribeToChromeSettings(): void {
    if (this.chromeSettingsSubscribed) return;
    this.chromeSettingsSubscribed = true;
    this.lastChromeSettingsKey = chromeSettingsKey();
    onEffectiveExtensionsChanged(() => {
      for (const window of this.windows) {
        window.relayout?.();
        window.requestRender();
      }
      this.config.requestShellRender();
    });
    onAnySettingChanged(() => {
      const key = chromeSettingsKey();
      if (key === this.lastChromeSettingsKey) return;
      this.lastChromeSettingsKey = key;
      this.config.requestShellRender();
    });
  }

  /** Add a window (or replace one with the same windowId, keeping its slot). */
  registerWindow(window: ShellWindow): void {
    const existing = this.windows.findIndex((w) => w.windowId === window.windowId);
    if (existing >= 0) {
      this.windows[existing] = window;
      if (existing === this.selectedIndex) this.noteWindowVisible(window.windowId);
    } else {
      this.windows.push(window);
      if (this.windows.length - 1 === this.selectedIndex) this.noteWindowVisible(window.windowId);
    }
    this.config.onWindowsChanged?.();
  }

  /** Move a window to the front of the most-recently-visible order. */
  private noteWindowVisible(windowId: string): void {
    this.mruWindowIds = this.mruWindowIds.filter((id) => id !== windowId);
    this.mruWindowIds.unshift(windowId);
  }

  /** Index of the most recently visible window still in the registry, or -1. */
  private mostRecentWindowIndex(): number {
    for (const id of this.mruWindowIds) {
      const index = this.windows.findIndex((w) => w.windowId === id);
      if (index >= 0) return index;
    }
    return -1;
  }

  removeWindow(windowId: string): void {
    const index = this.windows.findIndex((w) => w.windowId === windowId);
    if (index < 0) return;
    // Closing the Notifications window under a selected notification (from
    // the system menu) takes the selection with it.
    if (this.selectedNotification?.host.window.windowId === windowId) this.selectedNotification = null;
    const wasSelected = index === this.selectedIndex;
    this.windows.splice(index, 1);
    this.attention.delete(windowId);
    this.mruWindowIds = this.mruWindowIds.filter((id) => id !== windowId);
    if (wasSelected) {
      // Return to the most recently visible remaining window.
      const mruIndex = this.mostRecentWindowIndex();
      this.selectedIndex =
        mruIndex >= 0 ? mruIndex : Math.min(index, Math.max(0, this.windows.length - 1));
    } else if (this.selectedIndex > index) {
      this.selectedIndex--;
    }
    if (this.focus === "window" && (wasSelected || !this.windows.length)) {
      this.focus = "sidebar";
    }
    if (wasSelected) {
      // Hand the foreground to whatever is now selected.
      const next = this.windows[this.selectedIndex];
      if (next) {
        this.noteWindowVisible(next.windowId);
        next.setForeground?.(true);
        next.requestRender();
      }
    }
    this.config.onWindowsChanged?.();
    this.config.requestShellRender();
  }

  /** Close a window by id, if it is closeable (menu actions route here). */
  closeWindow(windowId: string): void {
    const window = this.windows.find((w) => w.windowId === windowId);
    if (!window || !window.closeable) return;
    try {
      window.close?.();
    } catch (error) {
      console.warn(`window ${window.windowId} close failed`, error);
    }
    this.removeWindow(window.windowId);
  }

  /** Close the foreground window via the shell (escape menu action). */
  closeForegroundWindow(): void {
    const window = this.foregroundWindow();
    if (window) this.closeWindow(window.windowId);
  }

  getWindows(): readonly ShellWindow[] {
    return this.windows;
  }

  setWindowAttention(windowId: string, attention: boolean): void {
    if (Boolean(this.attention.get(windowId)) === attention) return;
    this.attention.set(windowId, attention);
    this.config.requestShellRender();
  }

  setBatteryLevels(levels: Partial<ShellChromeState["battery"]>): void {
    this.battery = { ...this.battery, ...levels };
  }

  /**
   * Set or clear an app's top-bar tray icon (a small grayscale image, drawn
   * between the notification icons and the battery indicators). Small and
   * infrequently updated by design; not a framebuffer.
   */
  setTrayIcon(ownerId: string, icon: GrayImage | null): void {
    if (icon) {
      this.trayIcons.set(ownerId, icon);
    } else if (!this.trayIcons.delete(ownerId)) {
      return;
    }
    this.config.requestShellRender();
  }

  isScreenOn(): boolean {
    return this.screenOn;
  }

  /** Current G2, R1, and Wear OS watch battery levels (top bar, Glanceboard, assistant). */
  getBatteryLevels(): ShellChromeState["battery"] {
    return this.battery;
  }

  /**
   * The foreground app, or null when only the launcher is showing. The launcher
   * is a pinned window but counts as "no app" for the assistant's context.
   */
  getForegroundApp(): { appId: string; title: string } | null {
    const window = this.foregroundWindow();
    if (!window || window.appId === "launcher") return null;
    return { appId: window.appId, title: window.title };
  }

  noteUserActivity(nowMs = Date.now()): void {
    this.lastInputAtMs = nowMs;
  }

  /** Where input currently goes: the sidebar strip or the foreground window. */
  getFocus(): FocusKind {
    return this.focus;
  }

  /** True while a shell overlay (menu, dialog, voice input) is above the chrome. */
  hasOverlay(): boolean {
    return !this.stack.isAtBase();
  }

  /** The window whose sidebar icon is at screen (x, y), for mirror touches. */
  windowAtSidebarPoint(x: number, y: number): ShellWindow | null {
    const windows = this.switcherWindows();
    const index = this.chrome.windowIndexAt(x, y, windows.length);
    return index === null ? null : windows[index] ?? null;
  }

  /**
   * A touch from the phone's mirror on a selectable notification's icon:
   * select it and go into its detail view, as a touch on a window's icon
   * focuses that window. False when there is no such icon at (x, y).
   */
  focusNotificationAt(x: number, y: number): boolean {
    const keys = this.selectableNotificationKeys();
    const key = this.chrome.notificationKeyAt(x, y);
    if (!key || !keys.includes(key)) return false;
    this.selectNotification(key, keys.indexOf(key));
    const host = this.selectedNotification?.key === key ? this.selectedNotification.host.window : null;
    if (!host) return false;
    const alreadyFocused = this.isFocusTarget(host);
    this.focus = "window";
    if (!alreadyFocused) host.onFocus?.(this.lastInput);
    host.requestRender();
    this.syncInputFocus();
    return true;
  }

  /** Whether input focus currently targets this window (regardless of screen state). */
  private isFocusTarget(window: ShellWindow | undefined): boolean {
    return !!window && this.focus === "window" && this.foregroundWindow() === window;
  }

  /**
   * The window ordinary input reaches right now: the foreground window with
   * focus in-window, the screen on, and no shell overlay (system menu,
   * notification modal, voice dialog, alert) above it.
   */
  private inputTargetWindow(): ShellWindow | undefined {
    if (!this.screenOn || this.focus !== "window" || !this.stack.isAtBase() || this.activeVoiceLayer) {
      return undefined;
    }
    return this.foregroundWindow();
  }

  /**
   * Tell windows when they gain or lose input focus (ShellWindow.setInputFocus).
   * Called from every shell render request and the input/wake/sleep paths,
   * which between them follow every focus-affecting state change; it diffs
   * against the last notification, so calling it often is cheap.
   */
  private syncInputFocus(): void {
    const target = this.inputTargetWindow();
    const targetId = target?.windowId ?? null;
    if (targetId === this.inputFocusedWindowId) return;
    const previous = this.windows.find((w) => w.windowId === this.inputFocusedWindowId);
    this.inputFocusedWindowId = targetId;
    previous?.setInputFocus?.(false);
    target?.setInputFocus?.(true);
  }

  /** Turn the screen on (if off) and set focus. Returns whether it was off. */
  wake(focus: FocusKind, nowMs = Date.now()): boolean {
    this.lastInputAtMs = nowMs;
    const gaining = focus === "window" ? this.foregroundWindow() : undefined;
    const alreadyFocused = this.isFocusTarget(gaining);
    this.focus = focus;
    if (gaining && !alreadyFocused) gaining.onFocus?.(this.lastInput);
    if (this.screenOn) {
      this.syncInputFocus();
      return false;
    }
    this.screenOn = true;
    this.config.onScreenStateChanged(true);
    for (const window of this.windows) {
      window.setScreenOn?.(true);
    }
    // Refresh the foreground window; the compositor restored its retained
    // frame, but its content may be stale (e.g. a running stopwatch).
    this.foregroundWindow()?.requestRender();
    this.syncInputFocus();
    return true;
  }

  /**
   * Turn the screen off, closing any shell overlays. Sidebar selection is
   * kept, except that one on a notification goes back to its window.
   */
  sleep(): void {
    if (!this.screenOn) return;
    this.returnFromNotification();
    this.cancelEscapeMenuTimer();
    this.screenOn = false;
    this.stack.clearToBase();
    for (const window of this.windows) {
      window.setScreenOn?.(false);
    }
    this.syncInputFocus();
    this.config.onScreenStateChanged(false);
  }

  /** Foreground and focus a window by id (e.g. a wake path opening content in it). */
  focusWindow(windowId: string): void {
    const index = this.windows.findIndex((w) => w.windowId === windowId);
    if (index < 0) return;
    const target = this.windows[index];
    const alreadyFocused = this.isFocusTarget(target);
    this.setSelectedIndex(index);
    // Asked for by id, the Notifications window stays, whether or not it
    // was opened for a selected notification.
    this.endNotificationSelection(target === this.selectedNotification?.host.window);
    this.focus = "window";
    if (!alreadyFocused) target?.onFocus?.(this.lastInput);
    this.syncInputFocus();
  }

  /** Idle timeout: sleep if the configured timeout elapsed. Returns whether it slept. */
  applyScreenTimeout(nowMs = Date.now()): boolean {
    const timeoutMs = this.config.getScreenTimeoutMs();
    if (timeoutMs === null || !this.screenOn) return false;
    // An open voice dialog suspends the timeout: a long dictation or refine
    // has no button presses, but the screen must stay on for it. Sliding
    // lastInputAtMs forward also restarts the full timeout when it closes.
    // The keyboard dialog likewise: typing happens on the phone, not the
    // ring. An in-flight assistant turn suspends it for the same reason (a
    // tool loop can run for a while with no input); once the turn ends and
    // the Done/Follow-up menu is showing, the normal idle timeout resumes.
    // A foreground window that is playing video holds it off the same way.
    const foreground = this.foregroundWindow();
    if (this.activeVoiceLayer || this.activeKeyboardLayer || this.assistantSession?.isTurnActive() ||
        foreground?.isVoiceCapturing?.() || foreground?.keepsScreenOn?.()) {
      this.lastInputAtMs = nowMs;
      return false;
    }
    if (nowMs - this.lastInputAtMs < timeoutMs) return false;
    this.sleep();
    return true;
  }

  /**
   * Show a new notification in a shell modal over the app viewport, or queue
   * it behind the one already open (see NotificationModalQueue). If
   * notifications woke the screen, closing the last modal goes back to sleep
   * (matching the old sleep-popup behavior).
   */
  openNotificationModal(notificationKey: string, wokeScreen: boolean): void {
    if (!this.screenOn) return;
    this.notificationModals.post(notificationKey, wokeScreen);
    this.config.requestShellRender();
  }

  private pushNotificationModal(notificationKey: string): ShellModalLayer {
    const modal: ShellModalLayer = new ShellModalLayer(
      new SingleNotificationLayer(notificationKey, {
        origin: "new-notification-modal",
        onClose: () => this.closeNotificationModal(modal),
      }),
      this.config.actions,
      () => this.notificationModals.removed(modal, this.screenOn),
    );
    this.stack.push(modal);
    return modal;
  }

  private closeNotificationModal(modal: ShellModalLayer): void {
    // The queue first: it opens the next notification or puts the screen back
    // to sleep, and then ignores the removal below. remove() rather than a
    // pop, so a menu opened over the modal doesn't leave it stuck.
    this.notificationModals.closed(modal);
    this.stack.remove(modal);
    this.config.requestShellRender();
  }

  canShowExtensionOverlay(): boolean {
    return !this.activeVoiceLayer && !this.activeKeyboardLayer && this.stack.isAtBase();
  }

  /** Global APK surfaces cannot interrupt recording, review or another modal. */
  showExtensionOverlay(layer: ExtensionLayer, pauseWindow = true): boolean {
    if (!this.screenOn || this.activeVoiceLayer || this.activeKeyboardLayer || !this.stack.isAtBase()) return false;
    if (pauseWindow) this.foregroundWindow()?.handleInput({ type: "system-menu-opened" } as InputEvent, 0);
    this.stack.push(layer);
    this.config.requestShellRender();
    return true;
  }

  closeExtensionOverlay(layer: ExtensionLayer): void {
    this.stack.removeLayer(layer);
    this.config.requestShellRender();
  }

  /** Root back follows the selected navigation override. */
  returnFromAppRoot(): void {
    if (navigationPolicy().rootBack === "sleep") this.sleepAtAppRoot();
    else this.yieldFocusToSidebar();
  }

  /** An explicit Display off action always sleeps, independent of root back. */
  sleepAtAppRoot(): void {
    this.sleep();
    this.config.requestShellRender();
  }

  /** Explicitly focus the app switcher. */
  yieldFocusToSidebar(): void {
    if (this.focus === "sidebar") return;
    this.focus = "sidebar";
    // Repaint the window so its selection highlight dims to the unfocused
    // style this frame.
    this.foregroundWindow()?.requestRender();
    this.config.requestShellRender();
  }

  /**
   * Paint the shell surface: transparent chrome, or all-transparent when
   * asleep. The scene also carries the whole display's stereo depth.
   */
  paintScene(): Uint8Array {
    return this.screenOn ? encodeShellScene(this.stack.paintUndimmed(), uiDepth()) : encodeShellScene([]);
  }

  paintSurface(): Plane[] {
    if (!this.screenOn) {
      return singlePlane(new GrayImage(G2_LENS_WIDTH, G2_LENS_HEIGHT, 0));
    }
    return this.stack.paint();
  }

  /**
   * The brightness factor a shell overlay (a context menu) currently applies
   * to what lies beneath the shell surface, i.e. the window surfaces: 1 when
   * nothing dims them. Read after paintSurface; the controller forwards it to
   * the compositor, which cannot see the shell's layer stack.
   */
  underlayDim(): number {
    const dim = this.stack.baseDim();
    return this.screenOn && dim !== false ? dim : 1;
  }

  async receiveInput(event: InputEvent, frameId = 0): Promise<ShellInputOutcome> {
    if (!acceptInput(event)) return { shell: false, window: false };
    try {
      return await this.routeInput(event, frameId);
    } finally {
      // Whatever the input did (opened an overlay, moved focus, slept the
      // screen), windows learn about the resulting input-focus change.
      this.syncInputFocus();
    }
  }

  private async routeInput(event: InputEvent, frameId: number): Promise<ShellInputOutcome> {
    // Touch-down supplements gestures. It must not wake the screen, operate
    // menus, or cancel the hold-to-escape timer. Apps can opt into it later.
    if (event.type === "ring-press") {
      const window = this.foregroundWindow();
      if (this.screenOn && this.focus === "window" && this.stack.isAtBase() && window) {
        await window.handleInput(event, frameId);
        return { shell: false, window: true };
      }
      return { shell: false, window: false };
    }
    const previous = this.lastInput;
    this.lastInput = event;
    // A visible window may paint a source-dependent indicator (see
    // lastInputEvent); when the source class flips (watch <-> ring/arms),
    // repaint it so the indicator follows the device now in use.
    if (this.screenOn && previous && isWatchInput(previous) !== isWatchInput(event)) {
      this.foregroundWindow()?.requestRender();
    }
    const notificationPreviewOnTop = () => this.stack.topMatches((layer) =>
      typeof (layer as ExtensionLayer).claimsNotificationDismissGesture === "function" &&
      (layer as ExtensionLayer).claimsNotificationDismissGesture(),
    );

    // The stock lifecycle has already interpreted the physical double tap as
    // "wake". Keep that directionality if delivery is delayed or duplicated.
    // A notification preview is different: when the display was dark, the
    // lifecycle wake is the user's first tap on the visible card. Forward it
    // as the preview's ordinary click so the provider can open its reader.
    if (event.type === "display-wake") {
      this.lastInputAtMs = Date.now();
      const wokeScreen = !this.screenOn && this.wake(navigationPolicy().wakeFocus);
      if (notificationPreviewOnTop()) {
        await this.stack.handleInput({ type: "click", source: "ring", timestampMs: event.timestampMs });
        return { shell: true, window: false };
      }
      return { shell: wokeScreen, window: false };
    }

    // The wakeword is handled before the screen-off short-circuit so its
    // configured action can work from a dark screen. With the CFW the stock
    // Even AI app never launches, so the firmware does not power the display
    // for us either -- actions that need it wake the screen themselves.
    if (event.type === "wakeword") {
      if (this.foregroundWindow()?.isVoiceCapturing?.()) return { shell: true, window: false };
      const action = wakeWordActionSetting.get();
      if (action === "off") {
        return { shell: false, window: false };
      }

      this.lastInputAtMs = Date.now();
      const wokeScreen = !this.screenOn && this.wake(navigationPolicy().wakeFocus);
      if (action === "voice-input" && !this.activeVoiceLayer && !this.activeKeyboardLayer) {
        if (currentExtensionPlatform()?.openAssistant()) return { shell: true, window: false };
        if (this.assistantLayer) {
          // The assistant overlay is up; a wakeword continues that conversation.
          this.startAssistantFollowUp(true);
        } else {
          // Wakeword defaults the highlight to Send to Assistant.
          this.openVoiceDialog({ handsFree: true, defaultTarget: "assistant" });
        }
      }
      return { shell: wokeScreen || action === "voice-input", window: false };
    }

    this.lastInputAtMs = Date.now();

    // Anything but the long-press itself means the press ended (or the event
    // stream moved on), so the escape countdown stops.
    if (event.type !== "long-press") {
      this.cancelEscapeMenuTimer();
    }

    // Some firmware/input adapters emit a click while completing a hold. It
    // is part of the opening gesture, not a menu selection. Keep the guard
    // until the matching release; a click after release is deliberate input.
    if (event.type === "click" && this.appMenuGestureActive) {
      return { shell: true, window: false };
    }
    if (event.type === "click" && this.appMenuOpeningClickGuard) {
      this.appMenuOpeningClickGuard = false;
      if (this.appMenuOpeningClickGuardTimer !== null) { clearTimeout(this.appMenuOpeningClickGuardTimer); this.appMenuOpeningClickGuardTimer = null; }
      return { shell: true, window: false };
    }

    if (!this.screenOn) {
      // A wake and its first card tap can race across the firmware and host
      // lifecycle callbacks. Keep the exact notification layer interactive if
      // it is still present instead of dropping the gesture at the generic
      // screen-off guard.
      if (notificationPreviewOnTop() && ["click", "pointer-click", "double-click"].includes(event.type)) {
        this.wake("window");
        await this.stack.handleInput(event);
        return { shell: true, window: false };
      }
      if (event.type === "short-then-long-press" && navigationPolicy().tapHold === "switcher") {
        this.wake("sidebar");
        this.yieldFocusToSidebar();
        this.config.requestShellRender();
        return { shell: true, window: false };
      }
      if (event.type === "double-click") {
        this.wake(navigationPolicy().wakeFocus);
        return { shell: true, window: false };
      }
      return { shell: false, window: false };
    }

    // A provider may reserve the physical double tap for display power.
    // Handle it before app/back/sidebar routing so the same gesture cannot
    // also expose the switcher. Directional watch back is translated later.
    if (event.type === "double-click" && navigationPolicy().doubleTap === "sleep") {
      this.sleep();
      return { shell: true, window: false };
    }

    // An arrival preview is the only shell extension allowed to consume a
    // plain long press. Route it before the normal App actions menu so the
    // interruption can be dismissed without changing the meaning of holds
    // for ordinary apps, games, dictation, or protected overlays.
    if (
      event.type === "long-press" &&
      notificationPreviewOnTop()
    ) {
      await this.stack.handleInput(event);
      return { shell: true, window: false };
    }

    // By default long-press is the shell's own gesture: it opens the system menu
    // directly, never reaching the app — over the app's own context menu
    // too (the window closes that on system-menu-opened), while an already
    // open system menu just stays. Its later generic release is consumed
    // while the shell overlay is active and therefore cannot leak into the
    // app. The exception is a window that claims long-press for a move of
    // its own: it gets the press forwarded, with the escape timer running
    // so that holding the press long enough still opens the system menu.
    if (event.type === "long-press") {
      if (this.activeVoiceLayer || this.activeKeyboardLayer) return { shell: true, window: false };
      const window = this.foregroundWindow();
      if (!window) return { shell: true, window: false };
      // Match host-owned built-in IDs, not APK names or arbitrary gesture
      // claims. Paused games release the claim and use the normal app menu.
      const gameplayHold = GAMEPLAY_HOLD_ALLOWLIST.has(window.appId) && window.claimsLongPress?.();
      if (navigationPolicy().hold === "app-menu" && !gameplayHold) {
        if (this.stack.topMatches(layer => layer instanceof ShellOverlayMenuLayer)) return { shell: true, window: false };
        this.appMenuGestureActive = true;
        // A normal hold opens App actions; keeping it held retains the
        // host-controlled escape even if the APK stops responding.
        this.startEscapeMenuTimer();
        return { shell: true, window: await this.openAppActions(frameId) };
      }
      if (!this.stack.isAtBase()) {
        return { shell: true, window: false };
      }
      if (!window.holdToTalk && !window.claimsLongPress?.()) {
        this.openEscapeMenu();
        return { shell: true, window: false };
      }
      if (!window.holdToTalk) this.startEscapeMenuTimer();
      if (this.focus === "sidebar") {
        this.focus = "window";
        window.onFocus?.(this.lastInput);
      }
      // The window owns frameId from here (render or explicit finish).
      await window.handleInput(event, frameId);
      return { shell: true, window: true };
    }

    // The 2.2.9 tap-then-hold gesture is the app's context-menu gesture: it
    // goes to the foreground window (from the sidebar it focuses the window
    // first), which answers with its own menu or asks for the system menu
    // when it has none.
    if (event.type === "short-then-long-press") {
      if (navigationPolicy().tapHold === "switcher" || navigationPolicy().tapHold === "glanceboard") {
        this.stack.clearToBase();
        const foreground = this.foregroundWindow();
        await foreground?.handleInput(makeInputEvent({ type: "system-menu-opened" }), frameId);
        this.yieldFocusToSidebar();
        foreground?.requestRender();
        this.config.requestShellRender();
        return { shell: true, window: Boolean(foreground) };
      }
      const window = this.foregroundWindow();
      if (window?.holdToTalk) {
        this.openEscapeMenu();
        return { shell: true, window: false };
      }
      // Over the open system menu it switches to the app's context menu:
      // close the system menu and deliver the gesture to the window as
      // usual. A window without a menu of its own would only ask for the
      // system menu back, so for it the open menu stays.
      if (
        !this.activeVoiceLayer &&
        window?.hasAppMenu?.() &&
        this.stack.popIfTop((layer) => layer instanceof ShellOverlayMenuLayer)
      ) {
        this.config.requestShellRender();
      }
      if (this.activeVoiceLayer || !this.stack.isAtBase()) {
        return { shell: true, window: false };
      }
      if (!window) {
        return { shell: true, window: false };
      }
      if (this.focus === "sidebar") {
        this.focus = "window";
        window.onFocus?.(this.lastInput);
      }
      // A discrete tap-then-hold is itself the opening gesture. Some input
      // sources append a synthetic click; consume that one click before the
      // app menu can interpret it as its first selection.
      this.appMenuOpeningClickGuard = true;
      if (this.appMenuOpeningClickGuardTimer !== null) clearTimeout(this.appMenuOpeningClickGuardTimer);
      this.appMenuOpeningClickGuardTimer = setTimeout(() => {
        this.appMenuOpeningClickGuard = false;
        this.appMenuOpeningClickGuardTimer = null;
      }, 250);
      // The window owns frameId from here (render or explicit finish).
      await window.handleInput(event, frameId);
      return { shell: true, window: true };
    }
    if (event.type === "long-press-release") {
      // Under `hold: app-menu`, releases stay host-owned, including for the
      // built-in gameplay holds. A release must not reach a foreground app or
      // SDK adapter, where it can be interpreted as a click on the first row
      // (historically Display off). A later click is the only selection input.
      if (navigationPolicy().hold === "app-menu") {
        this.appMenuGestureActive = false;
        this.appMenuOpeningClickGuard = false;
        if (this.appMenuOpeningClickGuardTimer !== null) { clearTimeout(this.appMenuOpeningClickGuardTimer); this.appMenuOpeningClickGuardTimer = null; }
        return { shell: true, window: false };
      }
      this.activeVoiceLayer?.endCapture();
      if (this.activeVoiceLayer || !this.stack.isAtBase() || this.focus !== "window") {
        return { shell: true, window: false };
      }
      const window = this.foregroundWindow();
      if (!window) {
        return { shell: true, window: false };
      }
      await window.handleInput(event, frameId);
      return { shell: false, window: true };
    }

    if (!this.stack.isAtBase()) {
      await this.stack.handleInput(event);
      return { shell: true, window: false };
    }

    if (this.focus === "sidebar") {
      return this.handleSidebarInput(event);
    }

    const window = this.foregroundWindow();
    if (window) {
      // The window owns frameId from here (render or explicit finish).
      const delivered = isDirectionalInput(event) && !window.acceptsDirectional ? directionalFallback(event) : event;
      await window.handleInput(delivered, frameId);
      return { shell: false, window: true };
    }
    return { shell: false, window: false };
  }

  foregroundWindow(): ShellWindow | undefined {
    return this.windows[this.selectedIndex] ?? this.windows[0];
  }

  /**
   * Screen rect actually occupied by content, for cropping screenshots: the
   * foreground window's band (full height for a max-height window, the
   * vertical-position-dependent 288px band otherwise) plus the switcher: a
   * side strip's icon columns (minus the outer dead strip the one-column
   * variant leaves), or the row under it, and the window frame's sides. A
   * popup switcher lies within the band.
   */
  screenshotCropRect(): { x: number; y: number; width: number; height: number } {
    if (this.focus === "sidebar" && windowLayoutPolicy().switcherHeight === "display") {
      return { x: 0, y: 0, width: G2_LENS_WIDTH, height: G2_LENS_HEIGHT };
    }
    const appId = this.foregroundWindow()?.appId;
    const heightMode = this.foregroundWindow()?.heightMode ?? "min";
    const viewport = appViewportRect(heightMode, appId);
    let left = viewport.x, right = viewport.x + viewport.width;
    if (sidebarWidth(appId) > 0) {
      const strip = sidebarContentSpan(this.windows.length);
      left = Math.min(left, strip.left);
      right = Math.max(right, strip.right);
    }
    if (windowFramed(appId)) {
      left -= 1;
      right += 1;
    }
    const y = windowTop(heightMode, appId);
    const bottom = y + windowBandHeight(heightMode, appId) + switcherRowHeight(appId);
    return { x: left, y, width: right - left, height: bottom - y };
  }

  /**
   * Compact description of where input is currently going, for the frame
   * timing export: "which app was drawing" is usually the first thing you need
   * to interpret an input-to-display latency, and it is not recoverable from
   * the input event itself.
   */
  describeInputTarget(): string {
    const foreground = this.foregroundWindow()?.windowId ?? "none";
    if (!this.screenOn) return `fg=${foreground} target=screen-off`;
    if (this.activeVoiceLayer) return `fg=${foreground} target=voice`;
    if (this.activeKeyboardLayer) return `fg=${foreground} target=keyboard`;
    if (!this.stack.isAtBase()) return `fg=${foreground} target=shell-overlay`;
    return `fg=${foreground} target=${this.focus}`;
  }

  /**
   * The most recent input event received, from any source. Windows whose
   * appearance depends on the input device in use (e.g. the launcher's
   * defocused selection preview) can read it at paint time; the shell
   * repaints the foreground window whenever the source class changes, so
   * such paints stay current even while the window is not focused.
   */
  lastInputEvent(): InputEvent | null {
    return this.lastInput;
  }

  /** Whether the most recent input came from the watch (see lastInputEvent). */
  lastInputWasWatch(): boolean {
    return this.lastInput !== null && isWatchInput(this.lastInput);
  }

  /** Whether a window is the current input target (foreground + focus in-window). */
  isWindowFocused(windowId: string): boolean {
    return this.screenOn && this.focus === "window" && this.foregroundWindow()?.windowId === windowId;
  }

  /**
   * Whether a window's content is on screen: it's the foreground window and the
   * screen is on. Focus-independent — the app viewport stays visible while the
   * sidebar is focused (the sidebar is just the left strip).
   */
  isWindowVisible(windowId: string): boolean {
    return this.screenOn && this.foregroundWindow()?.windowId === windowId;
  }

  private handleSidebarInput(event: InputEvent): ShellInputOutcome {
    const type = isDirectionalInput(event) ? SWITCHER_SWIPES[switcherPosition()][event.type] : event.type;
    switch (type) {
      case "double-click":
        // A double-tap at the root (the app switcher selected) turns the
        // display off — from the ring and the watch scheme alike; watch-scheme
        // gestures wake it again (or a ring double-tap does).
        this.sleep();
        return { shell: true, window: false };
      case "scroll-up":
        this.moveSelection(-1);
        return { shell: true, window: false };
      case "scroll-down":
        this.moveSelection(1);
        return { shell: true, window: false };
      case "click":
        if (this.windows.length) {
          this.focus = "window";
          this.foregroundWindow()?.onFocus?.(this.lastInput);
          // Repaint the window now so its selection highlight reflects focus
          // this frame, not one frame late.
          this.foregroundWindow()?.requestRender();
        }
        return { shell: true, window: false };
      default:
        return { shell: false, window: false };
    }
  }

  /**
   * Step the switcher's selection through the windows and then, with a
   * bottom switcher, on through the notifications whose icons are showing,
   * wrapping round at either end.
   */
  private moveSelection(delta: number): void {
    const windows = this.switcherWindows();
    const keys = this.selectableNotificationKeys();
    const count = windows.length + keys.length;
    if (!count) return;
    let current = this.selectedIndex;
    const selected = this.selectedNotification;
    if (selected) {
      // Once its notification has gone, the selection stands between the
      // neighbours it left.
      const at = keys.indexOf(selected.key);
      current = windows.length + (at >= 0 ? at : Math.min(selected.index, keys.length) - (delta > 0 ? 1 : 0));
    }
    const next = (((current + delta) % count) + count) % count;
    if (next < windows.length) {
      this.selectSwitcherWindow(windows[next]!);
    } else {
      this.selectNotification(keys[next - windows.length]!, next - windows.length);
    }
  }

  /**
   * Windows with an icon in the switcher: all of them, except a
   * Notifications window opened just to show a selected notification.
   */
  private switcherWindows(): ShellWindow[] {
    const hidden = this.selectedNotification?.transient ? this.selectedNotification.host.window : null;
    return hidden ? this.windows.filter((window) => window !== hidden) : this.windows;
  }

  /**
   * Keys of the notifications the switcher's selection can move onto, left
   * to right: those whose icons are showing, with a bottom switcher (the
   * selection reaches them past the last window).
   */
  private selectableNotificationKeys(): string[] {
    return this.config.openNotificationsWindow && switcherPosition() === "bottom" ? this.chrome.notificationKeys() : [];
  }

  /** Move the switcher's selection onto a window, off a notification if it was on one. */
  private selectSwitcherWindow(window: ShellWindow): void {
    // Foreground first, so a Notifications window closing with the
    // notification selection can't hand the foreground elsewhere first.
    this.setSelectedIndex(this.windows.indexOf(window));
    this.endNotificationSelection(window === this.selectedNotification?.host.window);
  }

  /**
   * Move the switcher's selection onto a notification: the Notifications
   * window (opened in the background first, if need be) comes to the
   * foreground showing its detail view.
   */
  private selectNotification(key: string, index: number): void {
    const selected = this.selectedNotification;
    if (selected) {
      selected.index = index;
      if (selected.key === key) return;
      selected.host.stack.popThrough(selected.layer);
      selected.key = key;
      selected.layer = this.notificationDetailLayer(key);
      selected.host.stack.push(selected.layer);
      selected.host.requestRender();
      return;
    }
    const returnWindowId = this.foregroundWindow()?.windowId ?? null;
    const opened = this.config.openNotificationsWindow?.();
    const hostIndex = opened ? this.windows.indexOf(opened.window.window) : -1;
    if (!opened || hostIndex < 0) return;
    const host = opened.window;
    const layer = this.notificationDetailLayer(key);
    host.stack.push(layer);
    this.selectedNotification = { key, index, host, layer, transient: opened.opened, returnWindowId };
    if (hostIndex === this.selectedIndex) {
      host.requestRender();
    } else {
      this.setSelectedIndex(hostIndex);
    }
  }

  /**
   * Take the switcher's selection off its notification: the Notifications
   * window drops the detail view (and anything opened over it), and closes
   * if it was opened for the selection, unless `keepWindow` (it is the
   * window being selected instead).
   */
  private endNotificationSelection(keepWindow = false): void {
    const selected = this.selectedNotification;
    if (!selected) return;
    this.selectedNotification = null;
    selected.host.stack.popThrough(selected.layer);
    if (selected.transient && !keepWindow) {
      this.closeWindow(selected.host.window.windowId);
      return;
    }
    selected.host.requestRender();
    // A window opened for the selection is now one with an icon of its own.
    if (selected.transient) this.config.onWindowsChanged?.();
  }

  /** End a notification selection by going back to the window selected before it. */
  private returnFromNotification(): void {
    const selected = this.selectedNotification;
    if (!selected) return;
    const back = this.windows.find((window) => window.windowId === selected.returnWindowId);
    if (back) this.setSelectedIndex(this.windows.indexOf(back));
    this.endNotificationSelection(back === selected.host.window);
  }

  private notificationDetailLayer(key: string): SingleNotificationLayer {
    const layer: SingleNotificationLayer = new SingleNotificationLayer(key, {
      origin: "notification-tray",
      onClose: (_ctx, gone) => this.notificationDetailClosed(layer, gone),
    });
    return layer;
  }

  /**
   * A selected notification's detail view asked to close. Back (or a
   * double-tap) hands focus back to the switcher, its selection still on
   * the notification. Once the notification is gone (dismissed, or removed
   * by an action), the selection also moves on to the one that took its
   * place, or back to the windows when there are none left; that waits for
   * the current paint to finish, since a vanished notification closes its
   * view from inside its paint.
   */
  private notificationDetailClosed(layer: SingleNotificationLayer, gone: boolean): void {
    if (!gone) {
      if (this.selectedNotification?.layer === layer) this.yieldFocusToSidebar();
      return;
    }
    setTimeout(() => {
      const selected = this.selectedNotification;
      if (selected?.layer !== layer) return;
      this.focus = "sidebar";
      const keys = this.selectableNotificationKeys().filter((key) => key !== selected.key);
      const windows = this.switcherWindows();
      if (keys.length) {
        const index = Math.min(selected.index, keys.length - 1);
        this.selectNotification(keys[index]!, index);
      } else if (windows.length) {
        this.selectSwitcherWindow(windows[windows.length - 1]!);
      }
      this.config.requestShellRender();
    }, 0);
  }

  /** Change selection; the selected window is the foreground window. */
  private setSelectedIndex(index: number): void {
    if (index === this.selectedIndex) return;
    const previous = this.windows[this.selectedIndex];
    this.selectedIndex = index;
    const next = this.windows[index];
    if (next) this.noteWindowVisible(next.windowId);
    previous?.setForeground?.(false);
    next?.setForeground?.(true);
    next?.requestRender();
    this.config.onWindowsChanged?.();
  }

  // True while a voice-dialog open waits on prepareVoiceCapture (e.g. the
  // preview-mode permission prompt); a second tap must not queue another open.
  private voiceDialogPending = false;

  /** Open a single reviewed destination in the full shell, including permission and mic lifecycle. */
  openReviewedVoiceInput(
    target: VoiceSendTarget,
    canStart: () => boolean = () => true,
    onUnavailable: () => void = () => {},
    onClosed: () => void = () => {},
  ): () => void {
    const originWindowId = this.foregroundWindow()?.windowId;
    const shared = currentExtensionPlatform();
    if (originWindowId && shared?.feature("ui.composer")) {
      if (!canStart()) { onUnavailable(); return () => {}; }
      let completed = false;
      const cancel = shared.startHostComposer({ purpose: "message", target: target.id.slice(0, 512), label: target.label.slice(0, 100), initialText: "", maxText: 8000, originWindowId, current: canStart,
        complete: (status, text) => {
          if (completed) return; completed = true;
          if (status === "confirmed" && typeof text === "string" && text.trim() && canStart()) target.onSend(text);
          else if (status === "rejected") onUnavailable();
          onClosed();
        },
      });
      if (cancel) return () => { if (!completed) cancel(); };
      if (!completed) onUnavailable();
      return () => {};
    }
    let cancelled = false, owned: VoiceInputLayer | null = null;
    this.openVoiceDialog({ finishOnClick: true, defaultTarget: "app", sendTargets: [target], canStart: () => !cancelled && canStart(), onUnavailable, onClosed, onCreated: layer => { owned = layer; } });
    return () => { cancelled = true; if (owned) this.stack.removeLayer(owned); };
  }

  private openVoiceDialog(options: {
    finishOnClick?: boolean;
    handsFree?: boolean;
    defaultTarget: "assistant" | "app";
    sendTargets?: VoiceSendTarget[];
    canStart?: () => boolean;
    onUnavailable?: () => void;
    onCreated?: (layer: VoiceInputLayer) => void;
    onClosed?: () => void;
  }): void {
    if (this.config.voiceInputEnabled === false || this.voiceDialogPending || this.activeVoiceLayer || this.activeKeyboardLayer) {
      options.onUnavailable?.();
      return;
    }
    if (options.canStart && !options.canStart()) { options.onUnavailable?.(); return; }
    this.voiceDialogPending = true;
    void (async () => {
      let ready = true;
      try {
        ready = (await this.config.prepareVoiceCapture?.()) ?? true;
      } catch {
        ready = false;
      } finally {
        this.voiceDialogPending = false;
      }
      // Re-checked after the await: another path may have opened a dialog
      // (or torn down the base state) while a permission prompt was up.
      if (!ready || this.activeVoiceLayer || this.activeKeyboardLayer || (options.canStart && !options.canStart())) {
        options.onUnavailable?.(); return;
      }
      this.openVoiceDialogNow(options);
      this.config.requestShellRender();
    })();
  }

  private openVoiceDialogNow(options: {
    finishOnClick?: boolean;
    handsFree?: boolean;
    defaultTarget: "assistant" | "app";
    sendTargets?: VoiceSendTarget[];
    onCreated?: (layer: VoiceInputLayer) => void;
    onClosed?: () => void;
  }): void {
    const targets = options.sendTargets ?? this.buildVoiceSendTargets();
    let defaultIndex = targets.findIndex((target) => target.id === options.defaultTarget);
    if (defaultIndex < 0) defaultIndex = 0;
    // Skip the menu only for a hands-free (wakeword) capture aimed at the
    // assistant, when the user has opted into it.
    const autoSend =
      Boolean(options.handsFree) &&
      options.defaultTarget === "assistant" &&
      targets[defaultIndex]?.id === "assistant" &&
      assistantSkipConfirmationSetting.get();

    const layer = new VoiceInputLayer({
      actions: this.config.actions,
      onClosed: () => {
        if (this.activeVoiceLayer === layer) {
          this.activeVoiceLayer = null;
          voiceActivity.setActive(false);
          // The idle countdown restarts in full once voice input ends.
          this.noteUserActivity();
        }
        options.onClosed?.();
      },
      dismiss: () => {
        this.stack.popIfTop((top) => top === layer);
      },
      sendTargets: targets,
      defaultTargetIndex: defaultIndex,
      finishOnClick: options.finishOnClick ?? false,
      handsFree: options.handsFree ?? false,
      autoSend,
    });
    this.activeVoiceLayer = layer;
    voiceActivity.setActive(true);
    this.stack.push(layer);
    options.onCreated?.(layer);
    layer.startCapture();
  }

  /**
   * The send destinations offered by the voice dialog: the assistant (when an
   * API key is configured) and/or typing into the foreground window (when it
   * accepts text). Order fixes the menu row order.
   */
  private buildVoiceSendTargets(): VoiceSendTarget[] {
    const targets: VoiceSendTarget[] = [];
    if (this.isAssistantAvailable()) {
      targets.push({
        id: "assistant",
        label: "Send to Assistant",
        onSend: (text) => this.sendToAssistant(text),
      });
    }
    if (this.foregroundWindow()?.receiveTextInput) {
      targets.push({
        id: "app",
        label: "Type Into App",
        onSend: (text) => this.sendTextToForegroundWindow(text),
      });
    }
    // Guarantee at least one destination so the dialog is never a dead end.
    if (targets.length === 0) {
      targets.push({
        id: "app",
        label: "Type Into App",
        onSend: (text) => this.sendTextToForegroundWindow(text),
      });
    }
    return targets;
  }

  /** Deliver a text string to the foreground window (e.g. finalized voice input). */
  sendTextToForegroundWindow(text: string, options?: { submit?: boolean }): void {
    this.foregroundWindow()?.receiveTextInput?.(text, options);
  }

  /** Voice search has its own confirmation action and cannot invoke a send target. */
  private externalCaptureToken: object | null = null;
  /** App-owned capture UI receives drafts only; this API never issues Send authority. */
  startExternalAppCapture(
    windowId: string,
    onTranscript: (event: { text: string; isFinal: boolean }) => void,
    onStatus: (status: string) => void,
    onClosed: (reason: string) => void,
    canStart: () => boolean,
    ownTranscription?: { component: string; captureId: string },
  ): { finish: () => void; cancel: () => void } {
    const token = {};
    let closed = false, started = false, finishing = false;
    let transcriptSubscription: (() => void) | undefined, statusSubscription: (() => void) | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const available = () => !closed && this.externalCaptureToken === token && this.screenOn && this.foregroundWindow()?.windowId === windowId && canStart();
    const close = (reason: string) => {
      if (closed) return;
      closed = true; transcriptSubscription?.(); statusSubscription?.(); if (timer) clearTimeout(timer);
      if (this.externalCaptureToken === token) {
        this.externalCaptureToken = null; this.voiceDialogPending = false; voiceControlBridge.setAppTranscriptionProvider();
        if (started) { void Promise.resolve(this.config.actions.stopVoiceCapture()).catch(() => {}); voiceActivity.setActive(false); }
        this.noteUserActivity();
      }
      onClosed(reason);
    };
    const controls = {
      finish: () => {
        if (!available() || !started || finishing) return;
        finishing = true;
        if (timer) clearTimeout(timer); timer = setTimeout(() => close("transcript-timeout"), 95000);
        void Promise.resolve(this.config.actions.stopVoiceCapture()).catch(() => close("capture-unavailable"));
      },
      cancel: () => close("cancelled"),
    };
    if (this.voiceDialogPending || this.activeVoiceLayer || this.activeKeyboardLayer || voiceControlBridge.isCaptureHeld() || !this.screenOn || this.foregroundWindow()?.windowId !== windowId || !canStart()) {
      close("capture-unavailable"); return controls;
    }
    this.externalCaptureToken = token; this.voiceDialogPending = true;
    timer = setTimeout(() => close("capture-timeout"), 5 * 60000);
    void (async () => {
      let prepared = false;
      try { prepared = (await this.config.prepareVoiceCapture?.()) ?? true; } catch { /* Permission/capture failure remains a cancelled draft. */ }
      if (!prepared || !available() || this.activeVoiceLayer || this.activeKeyboardLayer || voiceControlBridge.isCaptureHeld()) { close("capture-unavailable"); return; }
      transcriptSubscription = voiceControlBridge.onTranscript(event => {
        if (!available()) { close("cancelled"); return; }
        if (typeof event.text !== "string" || event.text.length > 8000) { close("transcript-too-large"); return; }
        onTranscript({ text: event.text, isFinal: event.isFinal === true });
        if (finishing && event.isFinal) close("complete");
      });
      let subscribing = true;
      statusSubscription = voiceControlBridge.onStatus(state => { if (!subscribing && available()) onStatus(String(state.status).slice(0, 200)); });
      subscribing = false; started = true; voiceActivity.setActive(true); voiceControlBridge.setAppTranscriptionProvider(ownTranscription);
      try { await this.config.actions.startVoiceCapture(false); } catch { close("capture-unavailable"); }
    })();
    return controls;
  }

  startExternalAppSearch(windowId: string, label: string, onSearch: (query: string) => void, onClosed: () => void, canStart: () => boolean): () => void {
    let cancelled = false;
    let layer: VoiceSearchLayer | null = null;
    const cancel = () => { cancelled = true; if (layer) this.stack.removeLayer(layer); else onClosed(); };
    void (async () => {
      await Promise.resolve();
      if (cancelled || !canStart()) { onClosed(); return; }
      let ready = false;
      try { ready = (await this.config.prepareVoiceCapture?.()) ?? true; } catch { /* Permission refused. */ }
      if (!ready || cancelled || !canStart() || !this.screenOn || this.foregroundWindow()?.windowId !== windowId || this.activeVoiceLayer || this.activeKeyboardLayer || !this.stack.isAtBase()) { onClosed(); return; }
      layer = new VoiceSearchLayer({ actions: this.config.actions, label,
        onSearch: query => { if (!cancelled && this.screenOn && this.foregroundWindow()?.windowId === windowId) onSearch(query); },
        dismiss: () => { if (layer) this.stack.removeLayer(layer); },
        onClosed: () => { if (this.activeVoiceLayer === layer) this.activeVoiceLayer = null; voiceActivity.setActive(false); this.noteUserActivity(); onClosed(); },
      });
      this.activeVoiceLayer = layer; voiceActivity.setActive(true); this.stack.push(layer); layer.startCapture(); this.config.requestShellRender();
    })();
    return cancel;
  }

  /** Host-owned, paginated exact-text confirmation; never starts a microphone or auto-sends. */
  startMessagingReview(review: MessagingReview): () => void {
    const originWindowId = this.foregroundWindow()?.windowId;
    const shared = currentExtensionPlatform();
    if (originWindowId && review.composerText && review.acceptText && shared?.feature("ui.composer")) {
      let completed = false;
      const destination = review.destination ?? review.title;
      const cancel = shared.startHostComposer({ purpose: "message", target: destination.slice(0, 512), label: destination.slice(0, 100),
        initialText: review.composerText, maxText: 8000, originWindowId, current: review.current,
        complete: (status, text) => {
          if (completed) return; completed = true;
          if (status === "confirmed" && typeof text === "string" && review.current()) review.acceptText!(text);
          else review.cancel();
        },
      });
      if (cancel) return () => { if (!completed) cancel(); };
      if (!completed) review.cancel();
      return () => {};
    }
    let layer: MessagingReviewLayer | null = null;
    let cancelled = false;
    const windowId = this.foregroundWindow()?.windowId;
    const current = review.current;
    const scoped = { ...review, current: () => !cancelled && current() && this.screenOn && this.foregroundWindow()?.windowId === windowId };
    void Promise.resolve().then(() => {
      if (!scoped.current() || this.activeVoiceLayer || this.activeKeyboardLayer || !this.stack.isAtBase()) { review.cancel(); return; }
      layer = new MessagingReviewLayer(scoped, () => { if (layer) this.stack.removeLayer(layer); });
      this.stack.push(layer); this.noteUserActivity(); this.config.requestShellRender();
    });
    return () => { cancelled = true; if (layer) this.stack.removeLayer(layer); else review.cancel(); };
  }

  /** A capability-scoped app review. It has one explicit target and never auto-sends. */
  startExternalAppReview(windowId: string, label: string, initialText: string, onSend: (text: string) => void, onClosed: () => void): () => void {
    const shared = currentExtensionPlatform();
    if (shared?.feature("ui.composer") && this.screenOn && this.foregroundWindow()?.windowId === windowId) {
      let completed = false;
      const current = () => this.screenOn && this.foregroundWindow()?.windowId === windowId;
      const cancel = shared.startHostComposer({ purpose: "message", target: windowId.slice(0, 512), label: label.slice(0, 100), initialText, maxText: 8000, originWindowId: windowId, current,
        complete: (status, text) => {
          if (completed) return; completed = true;
          if (status === "confirmed" && typeof text === "string" && this.screenOn && this.foregroundWindow()?.windowId === windowId) onSend(text);
          onClosed();
        },
      });
      if (cancel) return () => { if (!completed) cancel(); };
      if (!completed) onClosed();
      return () => {};
    }
    let cancelled = false;
    let layer: VoiceInputLayer | null = null;
    const cancel = () => {
      cancelled = true;
      if (layer) this.stack.removeLayer(layer);
      else onClosed();
    };
    void (async () => {
      await Promise.resolve(); // Install the cancellation handle before any synchronous refusal.
      if (!initialText) {
        let ready = false;
        try { ready = (await this.config.prepareVoiceCapture?.()) ?? true; } catch { /* Permission refused. */ }
        if (!ready) { onClosed(); return; }
      }
      if (cancelled || !this.screenOn || this.foregroundWindow()?.windowId !== windowId || this.activeVoiceLayer || this.activeKeyboardLayer || !this.stack.isAtBase()) { onClosed(); return; }
      layer = new VoiceInputLayer({
        actions: this.config.actions, initialText, finishOnClick: true, autoSend: false,
        sendTargets: [{ id: "external-app", label: `Send via ${this.foregroundWindow()?.title ?? "app"}: ${label}`, onSend: (text) => { if (!cancelled && this.foregroundWindow()?.windowId === windowId) onSend(text); } }],
        onClosed: () => { if (this.activeVoiceLayer === layer) this.activeVoiceLayer = null; voiceActivity.setActive(false); this.noteUserActivity(); onClosed(); },
        dismiss: () => this.stack.removeLayer(layer),
      });
      this.activeVoiceLayer = layer; voiceActivity.setActive(true); this.stack.push(layer);
      if (!initialText) layer.startCapture();
      this.config.requestShellRender();
    })();
    return cancel;
  }

  /**
   * Open the voice dialog aimed at the foreground window. Called when the
   * user picks Voice input from the system menu (or an app asks via a
   * start-voice-input message). The menu click already ended the press, so
   * the dialog finishes on click instead of long-press-release.
   */
  startVoiceInput(): void {
    if (this.config.voiceInputEnabled === false) return;
    if (!this.screenOn || this.activeVoiceLayer || !this.stack.isAtBase()) return;
    // The transcript is aimed at the window whose menu requested it; the menu
    // entry point defaults the highlight to Type Into App.
    this.focus = "window";
    this.openVoiceDialog({ finishOnClick: true, defaultTarget: "app" });
    this.config.requestShellRender();
  }

  /**
   * Open the keyboard dialog: the voice dialog's typed twin, driven by the
   * phone's keyboard button (beside the mic button, whose wakeword this
   * mirrors: it wakes a dark screen too). Over the assistant overlay the text
   * continues that conversation; otherwise it goes to the usual destinations
   * with Send to Assistant highlighted. Returns the session the phone types
   * into (the already-open one if there is one), or null while a voice
   * dialog is up.
   */
  startKeyboardInput(): KeyboardInputSession | null {
    if (this.activeKeyboardLayer) return this.activeKeyboardLayer;
    if (this.activeVoiceLayer || this.foregroundWindow()?.isVoiceCapturing?.()) return null;
    if (!this.screenOn) this.wake("sidebar");
    if (currentExtensionPlatform()?.openAssistant("text-entry")) return null;
    const assistantLayer = this.assistantLayer;
    const assistantSession = this.assistantSession;
    let targets: VoiceSendTarget[];
    let defaultIndex = 0;
    if (assistantLayer && assistantSession) {
      targets = [
        {
          id: "assistant",
          label: "Send",
          onSend: (text) => this.runAssistantTurn(assistantSession, assistantLayer, text),
        },
      ];
    } else {
      targets = this.buildVoiceSendTargets();
      defaultIndex = Math.max(0, targets.findIndex((target) => target.id === "assistant"));
    }
    const layer = new KeyboardInputLayer({
      actions: this.config.actions,
      onClosed: () => {
        if (this.activeKeyboardLayer === layer) {
          this.activeKeyboardLayer = null;
          this.config.onKeyboardInputChanged?.(null);
          // The idle countdown restarts in full once keyboard input ends.
          this.noteUserActivity();
        }
      },
      dismiss: () => {
        this.stack.popIfTop((top) => top === layer);
        // A send or discard from the phone side arrives outside the input
        // path, so nothing else asks for the repaint.
        this.config.requestShellRender();
      },
      sendTargets: targets,
      defaultTargetIndex: defaultIndex,
    });
    this.activeKeyboardLayer = layer;
    this.stack.push(layer);
    this.config.onKeyboardInputChanged?.(layer);
    this.config.requestShellRender();
    return layer;
  }

  isAssistantAvailable(): boolean {
    const current = this.getAssistantConversations().current();
    return this.resolveAssistantConfiguration(current.model, current.reasoning) !== null;
  }

  /** Observe assistant turns (see AssistantActivityEvent). */
  onAssistantActivity(listener: (event: AssistantActivityEvent) => void): () => void {
    this.assistantActivityListeners.add(listener);
    return () => {
      this.assistantActivityListeners.delete(listener);
    };
  }

  /** Observe showAlert popups (mirrored to the watch). */
  onAlertShown(listener: (text: string) => void): () => void {
    this.alertListeners.add(listener);
    return () => {
      this.alertListeners.delete(listener);
    };
  }

  private emitAssistantActivity(event: AssistantActivityEvent): void {
    for (const listener of Array.from(this.assistantActivityListeners)) {
      try {
        listener(event);
      } catch (error) {
        console.warn("assistant activity listener failed", error);
      }
    }
  }

  /**
   * Dismiss the assistant overlay (cancelling any in-flight turn), as Done
   * would. Returns whether an overlay was actually open to close.
   */
  closeAssistant(): boolean {
    return this.closeAssistantLayer();
  }

  private ensureAssistantSession(): AssistantSession | null {
    return this.getAssistantConversations().ensureSession();
  }

  private resolveAssistantConfiguration(
    model: AssistantModel = this.conversations?.current().model ?? assistantModelSetting.get(),
    reasoning: ReasoningLevel = this.conversations?.current().reasoning ?? "default",
  ): AssistantBackendConfig | null {
    const fallback = this.resolveBaseAssistantConfiguration(model, reasoning);
    const provider = currentExtensionPlatform()?.feature("assistant");
    return provider ? { kind: "extension", component: provider.component, generation: provider.generation, fallback } : fallback;
  }
  private resolveBaseAssistantConfiguration(
    model: AssistantModel = this.conversations?.current().model ?? assistantModelSetting.get(),
    reasoning: ReasoningLevel = this.conversations?.current().reasoning ?? "default",
  ): AssistantBackendConfig | null {
    if (assistantBackendSetting.get() === "external") {
      const host = assistantBridgeHostSetting.get().trim();
      const token = assistantBridgeTokenSetting.get();
      if (!host || !token) return null;
      const port = parseInt(assistantBridgePortSetting.get(), 10) || 8790;
      return { kind: "external", bridge: { host, port, token } };
    }
    const llm = resolveAssistantModel(model, {
      anthropic: anthropicApiKeySetting.get(),
      openai: openAiApiKeySetting.get(),
    });
    if (llm && reasoning !== "default" && llm.effort) llm.effort = reasoning;
    return llm ? { kind: "direct", llm } : null;
  }

  private buildAssistantContext(): AssistantContext {
    const foreground = this.getForegroundApp();
    return {
      foregroundApp: foreground?.appId ?? null,
      foregroundTitle: foreground?.title ?? null,
      screenOn: this.screenOn,
      localTime: formatAssistantTime(new Date()),
      headsetBattery: this.battery.headset,
    };
  }

  /**
   * Start (or continue) an assistant conversation from a finalized utterance.
   * Opens the assistant overlay if it isn't already up; a follow-up reuses the
   * existing session and overlay.
   */
  sendToAssistant(text: string, showOverlay = true): void {
    text = text.trim();
    if (!text) return;
    const session = this.ensureAssistantSession();
    if (!session) {
      this.showAlert(
        assistantBackendSetting.get() === "external"
          ? "Configure the agent bridge host and token in Settings."
          : global.isIOS ? "Set an OpenAI or Anthropic API key in Settings." : "Set an API key or download the on-phone model in Settings.",
      );
      return;
    }
    if (session.isTurnActive()) {
      this.showAlert("The assistant is still working on the previous request");
      return;
    }
    if (!this.screenOn) this.wake("sidebar");
    if (!showOverlay || this.foregroundWindow()?.appId === "ai-chat") {
      this.runAssistantTurn(session, null, text);
      return;
    }
    let layer = this.assistantLayer;
    if (!layer) {
      const created = new AssistantLayer(this.config.actions, {
        onFollowUp: () => this.startAssistantFollowUp(),
        onCancel: () => this.assistantSession?.cancel(),
        onClose: () => this.closeAssistantLayer(),
        onRemoved: () => {
          // Removed by any path (Done, or the screen sleeping mid-conversation):
          // stop the turn and drop the overlay reference; keep shared history.
          this.assistantSession?.cancel();
          if (this.assistantLayer === created) this.assistantLayer = null;
          this.emitAssistantActivity({ phase: "closed", text: "" });
        },
      });
      layer = created;
      this.assistantLayer = created;
      this.stack.push(created);
    }
    this.runAssistantTurn(session, layer, text);
    this.config.requestShellRender();
  }

  private runAssistantTurn(session: AssistantSession, layer: AssistantLayer | null, text: string): void {
    if (session.isTurnActive()) return;
    layer?.startTurn();
    let replySoFar = "";
    this.emitAssistantActivity({ phase: "thinking", text: "" });
    session.sendUtterance(text, this.buildAssistantContext(), {
      onTextDelta: (delta, textSoFar) => {
        replySoFar = textSoFar;
        layer?.onTextDelta(delta, textSoFar);
        this.emitAssistantActivity({ phase: "streaming", text: textSoFar });
      },
      onToolActivity: (label) => layer?.onToolActivity(label),
      onTurnDone: () => {
        layer?.onTurnDone();
        this.emitAssistantActivity({ phase: "done", text: replySoFar });
      },
      onError: (message) => {
        layer?.onError(message);
        this.emitAssistantActivity({ phase: "error", text: message });
      },
    });
  }

  /**
   * Record another utterance in the current assistant conversation. handsFree
   * (from a wakeword over the overlay) starts the mic immediately; otherwise
   * (the Follow-up menu button) a click ends the utterance.
   */
  private startAssistantFollowUp(handsFree = false): void {
    const layer = this.assistantLayer;
    const session = this.assistantSession;
    if (!layer || !session || this.activeVoiceLayer || this.activeKeyboardLayer) return;
    const voice = new VoiceInputLayer({
      actions: this.config.actions,
      onClosed: () => {
        if (this.activeVoiceLayer === voice) {
          this.activeVoiceLayer = null;
          voiceActivity.setActive(false);
          this.noteUserActivity();
        }
      },
      dismiss: () => {
        this.stack.popIfTop((top) => top === voice);
      },
      sendTargets: [
        { id: "assistant", label: "Send", onSend: (text) => this.runAssistantTurn(session, layer, text) },
      ],
      finishOnClick: !handsFree,
      handsFree,
      autoSend: handsFree && assistantSkipConfirmationSetting.get(),
    });
    this.activeVoiceLayer = voice;
    voiceActivity.setActive(true);
    this.stack.push(voice);
    voice.startCapture();
    this.config.requestShellRender();
  }

  private closeAssistantLayer(): boolean {
    // Popping fires the layer's onRemoved, which cancels the turn and clears
    // this.assistantLayer. popThrough also closes anything stacked above the
    // overlay (a follow-up voice dialog, an alert), so a close command works
    // no matter what the conversation is showing.
    const layer = this.assistantLayer;
    const closed = layer ? this.stack.popThrough(layer) : false;
    this.noteUserActivity();
    this.config.requestShellRender();
    return closed;
  }

  /** Show a brief text popup on the lenses (assistant show_alert / notices). */
  showAlert(text: string): void {
    if (!this.screenOn) this.wake("sidebar");
    for (const listener of Array.from(this.alertListeners)) {
      try {
        listener(text);
      } catch (error) {
        console.warn("alert listener failed", error);
      }
    }
    const layer = new ShellAlertLayer(text, () => {
      this.stack.popIfTop((top) => top === layer);
      this.config.requestShellRender();
    });
    this.stack.push(layer);
    this.config.requestShellRender();
  }

  private startEscapeMenuTimer(): void {
    this.cancelEscapeMenuTimer();
    this.escapeMenuTimer = setTimeout(() => {
      this.escapeMenuTimer = null;
      this.openEscapeMenu();
    }, LONG_PRESS_ESCAPE_MENU_MS);
  }

  private cancelEscapeMenuTimer(): void {
    if (this.escapeMenuTimer !== null) {
      clearTimeout(this.escapeMenuTimer);
      this.escapeMenuTimer = null;
    }
  }

  /**
   * A window's answer to tap-then-hold when it has no context menu of its
   * own: open the system menu in its place, so both gestures land on the
   * same menu. Ignored unless the window is still the foreground one.
   */
  openSystemMenu(windowId: string): void {
    if (this.foregroundWindow()?.windowId !== windowId) return;
    this.openEscapeMenu();
  }

  private async openAppActions(frameId = 0): Promise<boolean> {
    if (!this.screenOn || this.stack.topMatches(layer => layer instanceof ShellOverlayMenuLayer)) return false;
    const foreground = this.foregroundWindow();
    if (!foreground) return false;
    if (this.stack.isAtBase() && foreground.hasAppMenu?.()) {
      this.focusWindow(foreground.windowId);
      await foreground.handleInput(makeInputEvent({ type: "short-then-long-press", source: "ring" }), frameId);
      return true;
    }
    let layer: ShellOverlayMenuLayer;
    const items = appActionItems(
      [],
      () => this.sleepAtAppRoot(),
      () => this.openEscapeMenu(),
      foreground.closeable ? () => this.closeWindow(foreground.windowId) : undefined,
    );
    if (!items.length) { this.openEscapeMenu(); return false; }
    layer = new ShellOverlayMenuLayer(items, undefined, () => this.focusWindow(foreground.windowId), appMenuPolicy().title ?? foreground.title);
    const safeIndex = items.findIndex(item => item.label !== "Display off" && !(typeof item.disabled === "function" ? item.disabled() : item.disabled));
    if (safeIndex >= 0) layer.selectItem(safeIndex);
    layer.keepWindowFocus = true;
    const menuStack = new LayerStack({ paint: () => new GrayImage(1, 1, 0), handleInput: () => {} }, this.config.actions);
    menuStack.push(layer);
    if (presentAppMenu(foreground.windowId, appMenuPolicy().title ?? foreground.title, items, layer, { stack: menuStack, actions: this.config.actions }, () => menuStack.removeLayer(layer))) return false;
    this.stack.push(layer);
    this.config.requestShellRender();
    return false;
  }

  /**
   * The system/escape menu: the entries every window shares (Focus app
   * switcher, Voice input, Brightness, Close window) plus Debug. Shell-owned and
   * shell-drawn (never the app's), so an unresponsive app can always be
   * closed. It opens for long-press (over the app's own menu too), after an
   * extended hold in a window that claims long-press, and on a window's
   * request when tap-then-hold finds it has no menu of its own.
   */
  private openEscapeMenu(): void {
    if (!this.screenOn || this.activeVoiceLayer || this.activeKeyboardLayer) return;
    if (!this.stack.isAtBase() && navigationPolicy().hold !== "app-menu") return;
    if (this.stack.topMatches(layer => layer instanceof ShellOverlayMenuLayer)) return;
    const foreground = this.foregroundWindow();
    if (!foreground) return;
    const items: MenuItem[] = [];
    const grouped = appMenuPolicy().systemActionsLast;
    const hasOverlay = !this.stack.isAtBase();
    if (foreground.closeable && !hasOverlay) {
      items.push({
        label: "Close app",
        onSelect: (ctx) => {
          // Pop the menu first (its onRemoved returns focus to the sidebar),
          // then close the window the menu was opened over.
          ctx.stack.pop();
          this.closeForegroundWindow();
        },
      });
    }
    // Close app sits first but the menu opens on Focus app switcher, so a
    // reflexive tap never closes the window.
    const initialSelection = items.length;
    items.push(
      {
        // Defocus the app (hand focus to the sidebar) without closing it: the
        // reliable way out of an app that consumes double-click. Closing the
        // menu is what yields focus, so popping is the whole action.
        label: grouped ? "Return to app" : "Focus app switcher",
        onSelect: (ctx) => {
          ctx.stack.pop();
        },
      },
      ...(this.config.voiceInputEnabled === false ? [] : [{
        label: "Voice input",
        onSelect: (ctx) => {
          // The transcript is aimed at the foreground window, so keep focus
          // there through the pop instead of yielding to the sidebar.
          layer.keepWindowFocus = true;
          ctx.stack.pop();
          this.startVoiceInput();
        },
      }]),
    );
    if (grouped) items.splice(initialSelection + 1, 0, { label: "Display off", onSelect: ctx => { ctx.stack.pop(); this.sleepAtAppRoot(); } });
    if (hasOverlay) {
      const voiceIndex = items.findIndex(item => item.label === "Voice input");
      if (voiceIndex >= 0) items.splice(voiceIndex, 1);
    }
    if (!hasOverlay && brightnessSetting.get() !== "auto") {
      items.push({
        label: "Brightness",
        onSelect: (ctx) => {
          ctx.stack.pop();
          ctx.stack.push(new BrightnessPickerLayer(() => this.yieldFocusToSidebar()));
          this.config.requestShellRender();
        },
      });
    }
    if (!hasOverlay) items.push({
      label: "Debug",
      onSelect: (ctx) => {
        ctx.stack.pop();
        this.openToolDebugDialog();
      },
    });
    // Gesture help: tap-then-hold switches to the app's own context menu,
    // when the app has one (otherwise it opens this very menu, not worth a
    // hint).
    if (grouped && !hasOverlay && foreground.hasAppMenu?.()) items.push({ label: "App actions", onSelect: async ctx => { ctx.stack.pop(); await this.openAppActions(); } });
    const footer = ["switcher", "glanceboard"].includes(navigationPolicy().tapHold)
      ? gestureHints([[GESTURE_SHORT_THEN_LONG_PRESS, "app switcher"]])
      : foreground.hasAppMenu?.() && !foreground.holdToTalk
        ? gestureHints([[GESTURE_SHORT_THEN_LONG_PRESS, "app menu"]])
      : undefined;
    const layer = new ShellOverlayMenuLayer(items, footer, () => grouped ? this.focusWindow(foreground.windowId) : this.yieldFocusToSidebar(), grouped ? appMenuPolicy().systemTitle : undefined);
    layer.selectItem(initialSelection);
    this.stack.push(layer);
    // Tell the window the system menu opened over it: an app with its own
    // context menu up closes it, so the two context menus never stack.
    void foreground.handleInput(makeInputEvent({ type: "system-menu-opened" }), 0);
    this.config.requestShellRender();
  }

  /**
   * Escape menu > Debug: list the assistant tools registered for the
   * foreground window's app (live or gated). System-wide "always" tools are
   * constant and omitted.
   */
  private openToolDebugDialog(): void {
    const foreground = this.foregroundWindow();
    const appId = foreground?.appId ?? null;
    const entries = appId
      ? toolRegistry
          .listToolsForDebug()
          .filter(
            (entry) =>
              entry.spec.name.startsWith(`app.${appId}.`) || entry.windowId === foreground!.windowId,
          )
      : [];
    this.stack.push(new ToolDebugMenuLayer(appId, entries, () => this.yieldFocusToSidebar()));
    this.config.requestShellRender();
  }

  private chromeState(): ShellChromeState {
    return {
      windows: this.switcherWindows().map((window) => ({
        windowId: window.windowId,
        title: window.title,
        attention: Boolean(this.attention.get(window.windowId)),
        drawIcon: window.drawIcon,
      })),
      selectedIndex: this.selectedNotification ? -1 : this.selectedIndex,
      selectedNotificationKey: this.selectedNotification?.key ?? null,
      focus: this.focus,
      foregroundHeightMode: this.foregroundWindow()?.heightMode ?? "min",
      foregroundAppId: this.foregroundWindow()?.appId,
      compactChrome: this.foregroundWindow()?.compactChrome,
      battery: this.battery,
      trayIcons: Array.from(this.trayIcons.keys())
        .sort()
        .map((key) => this.trayIcons.get(key)!),
    };
  }
}

export const shell = new Shell();

const ASSISTANT_WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const ASSISTANT_MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** Human-readable local time for the assistant's per-turn context. */
function formatAssistantTime(date: Date): string {
  let hours = date.getHours();
  const minutes = date.getMinutes().toString().padStart(2, "0");
  const meridiem = hours >= 12 ? "PM" : "AM";
  hours = hours % 12;
  if (hours === 0) hours = 12;
  const day = `${ASSISTANT_WEEKDAYS[date.getDay()]} ${ASSISTANT_MONTHS[date.getMonth()]} ${date.getDate()}`;
  return `${day}, ${hours}:${minutes} ${meridiem}`;
}

export function rawInputEventToInputEvent(event: RawInputEvent): InputEvent {
  return { ...makeInputEvent(rawInputEventToPayload(event)),
    ...(event.ringInput ? { ringInput: { ...event.ringInput } } : {}) };
}

function rawInputEventToPayload(event: RawInputEvent): InputEventPayload {
  if (event.kind === "sys-event") {
    if (event.eventType === OsEventTypeList.RING_PRESS_EVENT &&
        (event.eventSource === EventSourceType.TOUCH_EVENT_FORM_DUMMY_NULL ||
         event.eventSource === EventSourceType.TOUCH_EVENT_FROM_RING)) {
      // The firmware emits this dedicated ID only for full raw source 4.
      // Its stock sender leaves the source unspecified for extension 14.
      return { type: "ring-press", source: "ring" };
    } else if (event.eventType === OsEventTypeList.RING_PRESS_EVENT &&
               event.eventSource === EventSourceType.TOUCH_EVENT_FROM_WATCH) {
      // Synthetic: the watch pad's (or the phone's watch pad's) touch-down.
      return { type: "ring-press", source: "watch" };
    } else if (event.eventType === OsEventTypeList.CLICK_EVENT) {
      return {
        type: "click",
        source: eventSourceToString(event.eventSource),
      };
    } else if (event.eventType === OsEventTypeList.DOUBLE_CLICK_EVENT) {
      return {
        type: "double-click",
        source: eventSourceToString(event.eventSource),
      };
    } else if (event.eventType === OsEventTypeList.SCROLL_BOTTOM_EVENT) {
      return scrollEvent("scroll-down", event.eventSource);
    } else if (event.eventType === OsEventTypeList.SCROLL_TOP_EVENT) {
      return scrollEvent("scroll-up", event.eventSource);
    } else if (event.eventType === OsEventTypeList.RING_LONG_PRESS_EVENT) {
      // CFW-forwarded long-press (replaces the firmware's force-quit dialog).
      // Current CFW supplies the physical source for ring and temple presses;
      // eventSourceToString's ring fallback keeps older CFW builds compatible.
      return { type: "long-press", source: eventSourceToString(event.eventSource) };
    } else if (event.eventType === OsEventTypeList.RING_LONG_PRESS_RELEASE_EVENT) {
      return { type: "long-press-release", source: eventSourceToString(event.eventSource) };
    } else if (event.eventType === OsEventTypeList.SHORT_THEN_LONG_PRESS_EVENT) {
      return { type: "short-then-long-press", source: eventSourceToString(event.eventSource) };
    }
  } else if (event.kind === "watch-gesture") {
    // Synthetic, from the Wear OS remote (app/g2/wear-remote.ts); the glasses
    // firmware never produces these.
    if (event.eventType === WatchGestureType.SWIPE_LEFT) {
      return { type: "swipe-left", source: "watch" };
    } else if (event.eventType === WatchGestureType.SWIPE_RIGHT) {
      return { type: "swipe-right", source: "watch" };
    } else if (event.eventType === WatchGestureType.SWIPE_UP) {
      return { type: "swipe-up", source: "watch" };
    } else if (event.eventType === WatchGestureType.SWIPE_DOWN) {
      return { type: "swipe-down", source: "watch" };
    }
  } else if (event.kind === "even-ai") {
    // sid 0x07 EvenAIDataPackage; eventType carries eEvenAIStatus. Only the
    // wakeword interests us -- ENTER means the user manually opened the stock
    // assistant, and EXIT is it tearing down.
    if (event.eventType === EvenAIStatus.EVEN_AI_WAKE_UP) {
      return { type: "wakeword" };
    }
  } else if (event.kind === "display-wake") {
    // Outside an EvenHub page the firmware consumes the physical double tap
    // itself and reports only that the stock display lifecycle woke.
    return { type: "display-wake" };
  } else if (event.kind === "text-click") {
    if (event.eventType === OsEventTypeList.SCROLL_BOTTOM_EVENT) {
      return scrollEvent("scroll-down", event.eventSource);
    } else if (event.eventType === OsEventTypeList.SCROLL_TOP_EVENT) {
      return scrollEvent("scroll-up", event.eventSource);
    }
  }
  return {
    type: "unknown",
    kind: event.kind,
    eventSource: event.eventSource,
    eventType: event.eventType,
  };
}

/** Preserve explicit sources; stock scroll notifications usually omit them. */
function scrollEvent(type: "scroll-up" | "scroll-down", eventSource: number): InputEventPayload {
  return eventSource === EventSourceType.TOUCH_EVENT_FROM_WATCH ||
    eventSource === EventSourceType.TOUCH_EVENT_FROM_RING ||
    eventSource === EventSourceType.TOUCH_EVENT_FROM_GLASSES_L ||
    eventSource === EventSourceType.TOUCH_EVENT_FROM_GLASSES_R
    ? { type, source: eventSourceToString(eventSource) } : { type };
}

function eventSourceToString(eventSource: number): InputSource {
  if (eventSource === EventSourceType.TOUCH_EVENT_FROM_RING) {
    return "ring";
  } else if (eventSource === EventSourceType.TOUCH_EVENT_FROM_GLASSES_L) {
    return "left-arm";
  } else if (eventSource === EventSourceType.TOUCH_EVENT_FROM_GLASSES_R) {
    return "right-arm";
  } else if (eventSource === EventSourceType.TOUCH_EVENT_FROM_WATCH) {
    return "watch";
  }
  return "ring";
}

export function inputEventToString(event: InputEvent): string {
  switch (event.type) {
    case "ring-press":
      return `Press from ${event.source}`;
    case "click":
      return `Click from ${event.source}`;
    case "double-click":
      return `Double click from ${event.source}`;
    case "scroll-up":
      return `Scroll up`;
    case "scroll-down":
      return `Scroll down`;
    case "long-press":
      return `Long press from ${event.source}`;
    case "long-press-release":
      return `Long press release from ${event.source}`;
    case "short-then-long-press":
      return `Short then long press from ${event.source}`;
    case "swipe-left":
      return `Swipe left from ${event.source}`;
    case "swipe-right":
      return `Swipe right from ${event.source}`;
    case "swipe-up":
      return `Swipe up from ${event.source}`;
    case "swipe-down":
      return `Swipe down from ${event.source}`;
    case "display-wake":
      return `Display wake`;
    case "wakeword":
      return `Wakeword`;
    case "system-menu-opened":
      return `System menu opened`;
    case "app-menu-selection":
    case "app-menu-closed":
    case "app-menu-fallback":
      return `App menu response`;
    default:
    case "unknown":
      return `Unknown event: ${event.kind} ${event.eventSource} ${event.eventType}`;
  }
}
