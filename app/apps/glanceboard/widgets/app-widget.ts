import { GrayImage } from '../../../graphics/image';
import { getDefaultSmallFont } from '../../../graphics/ui-fonts';
import { truncateText } from '../../../graphics/textwrap';
import { weatherBridge } from '../../../native/weather';
import type { GlanceWidget } from '../widget';
import { appGlanceContent, appGlanceSources, onAppGlanceChanged, requestAppGlance } from '../app-content';

/** Generic registered widget. No application names, layouts, or app-specific data fetches. */
export class AppGlanceWidget implements GlanceWidget {
  private unsubscribe: (() => void) | null = null;
  private contextUnsubscribe: (() => void) | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private active = false;
  constructor(private readonly key: string) {}
  private source() { return appGlanceSources().find(source => source.key === this.key); }
  start(requestRender: () => void): void {
    this.stop(); this.active = true;
    let registry = JSON.stringify(this.source());
    const arm = () => {
      if (this.timer !== null) clearTimeout(this.timer);
      const content = appGlanceContent(this.key);
      const expires = Math.min(content?.expiresAt ?? Infinity, ...(content?.entries?.map(row => row.expiresAt) ?? []));
      this.timer = setTimeout(() => {
        this.timer = null; if (!this.active) return;
        requestAppGlance(this.key); requestRender(); arm();
      }, Math.max(250, Math.min(this.source()?.refreshMs ?? 30000, expires - Date.now())));
    };
    this.unsubscribe = onAppGlanceChanged(() => {
      if (!this.active) return;
      const next = JSON.stringify(this.source());
      if (next !== registry || !appGlanceContent(this.key)) { registry = next; requestAppGlance(this.key); }
      requestRender(); arm();
    });
    // Weather refreshes are shared/coalesced by the host and only observed while visible.
    if (this.source()?.uses.includes('weather')) this.contextUnsubscribe = weatherBridge.onStateChange(() => {
      if (this.active) requestAppGlance(this.key);
    });
    else requestAppGlance(this.key);
    arm();
  }
  stop(): void {
    this.active = false; this.unsubscribe?.(); this.unsubscribe = null;
    this.contextUnsubscribe?.(); this.contextUnsubscribe = null;
    if (this.timer !== null) clearTimeout(this.timer); this.timer = null;
  }
  paint(image: GrayImage): void {
    const font = getDefaultSmallFont(), step = font.lineHeight + 5, source = this.source();
    const content = appGlanceContent(this.key);
    if (source && image.height !== source.rows * 144) {
      image.drawText(font, 8, 8, truncateText(font, source.label, image.width - 16), 235);
      image.drawText(font, 8, 8 + step, source.rows === 2 ? 'Select in both vertical slots' : 'Select in one slot', 190); return;
    }
    if (source?.kind === 'scene' && content?.commands && !content.redacted) {
      for (const command of content.commands) {
        if (command.op === 'text') image.drawText(font, command.x, command.y, truncateText(font, command.text, command.width), command.value);
        else if (command.op === 'rect') image.fillRect(command.x, command.y, command.width, command.height, command.value);
        else if (command.op === 'bitmap') {
          const bitmap = new GrayImage(command.width, command.height, 0);
          const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
          let bits = 0, held = 0, pixel = 0;
          for (const char of command.bits) {
            if (char === '=') break;
            bits = (bits << 6) | alphabet.indexOf(char); held += 6;
            while (held >= 8) { held -= 8; const byte = (bits >> held) & 255;
              for (let bit = 7; bit >= 0 && pixel < bitmap.pixels.length; bit--) bitmap.pixels[pixel++] = (byte & (1 << bit)) ? command.value : 0;
            }
          }
          image.bitBlt(bitmap, command.x, command.y, { transparentZero: true });
        }
      }
      return;
    }
    const title = content?.title || source?.label || 'Unavailable widget';
    image.drawText(font, 8, 6, truncateText(font, title, image.width - 16), 235);
    if (content?.redacted || !content?.entries?.length) {
      image.drawText(font, 8, 6 + step, truncateText(font, content?.redacted ? 'Content previews disabled' : content?.emptyText || 'Unavailable', image.width - 16), 140); return;
    }
    const capacity = Math.max(0, Math.floor((image.height - 12 - step) / step));
    const overflow = content.entries.length > capacity;
    const visible = content.entries.slice(0, overflow ? Math.max(0, capacity - 1) : capacity);
    visible.forEach((row, index) => image.drawText(font, 8, 6 + (index + 1) * step,
      truncateText(font, row.detail ? `${row.title} · ${row.detail}` : row.title, image.width - 16), 190));
    if (overflow && capacity > 0) image.drawText(font, 8, 6 + capacity * step, 'More in app', 140);
  }
}
