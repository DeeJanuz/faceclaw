import { GrayImage } from '../../graphics/image';
import { wrapText, truncateText } from '../../graphics/textwrap';
import { getDefaultSmallFont } from '../../graphics/ui-fonts';
import type { Layer, LayerContext } from '../layers';
import type { InputEvent } from '../gestures';
import type { MessagingReview } from '../../assistant/messaging';

/** Every page must be displayed before the final physical confirmation. No tool can invoke input. */
export class MessagingReviewLayer implements Layer {
  readonly dimUnderneath = 0;
  private page = 0;
  private pages: string[][] = [];
  private layout = '';
  private paintedPage = -1;
  private paintedAt = 0;
  private closed = false;
  constructor(private review: MessagingReview, private dismiss: () => void) {}
  paint(_ctx: LayerContext, _below: () => GrayImage): GrayImage {
    const image = new GrayImage(640, 480, 1), font = getDefaultSmallFont();
    const lineHeight = Math.max(16, font.lineHeight + 4), bodyTop = 20 + 2 * lineHeight, footerTop = 480 - 2 * lineHeight - 12;
    const linesPerPage = Math.max(1, Math.floor((footerTop - bodyTop - 8) / lineHeight));
    const layout = `${font.fingerprintId}:${lineHeight}`;
    if (this.layout !== layout) {
      this.layout = layout; this.page = 0; this.paintedPage = -1;
      const lines = wrapText(font, this.review.text, 560, { preserveLeadingWhitespace: true });
      this.pages = []; for (let i = 0; i < lines.length; i += linesPerPage) this.pages.push(lines.slice(i, i + linesPerPage));
      if (!this.pages.length) this.pages.push(['']);
    }
    if (this.paintedPage !== this.page) { this.paintedPage = this.page; this.paintedAt = Date.now(); }
    image.drawText(font, 40, 20, truncateText(font, this.review.title, 560), 255);
    image.drawText(font, 40, 20 + lineHeight, `Page ${this.page + 1} of ${this.pages.length}`, 150);
    this.pages[this.page]!.forEach((line, i) => image.drawText(font, 40, bodyTop + i * lineHeight, line, 240));
    image.drawText(font, 40, footerTop, this.page < this.pages.length - 1 ? 'Tap: Next page' : `Tap: ${this.review.acceptLabel}`, 255);
    image.drawText(font, 40, footerTop + lineHeight, 'Scroll: pages   Double tap: Cancel', 150);
    return image;
  }
  handleInput(event: InputEvent, ctx: LayerContext): void {
    if (this.closed) return;
    if (!this.review.current()) { this.dismiss(); return; }
    if (event.type === 'double-click') { this.dismiss(); return; }
    if (event.type === 'scroll-up') this.page = Math.max(0, this.page - 1);
    else if (event.type === 'scroll-down' || event.type === 'click') {
      if (this.paintedPage !== this.page || Date.now() - this.paintedAt < 300 || event.timestampMs < this.paintedAt) return;
      if (this.page < this.pages.length - 1) this.page++;
      else if (event.type === 'click' && ['ring','left-arm','right-arm','watch'].includes(event.source)) {
        this.closed = true; this.review.accept(); this.dismiss(); return;
      }
    }
    ctx.actions.requestRender();
  }
  onRemoved(): void { if (!this.closed) { this.closed = true; this.review.cancel(); } this.review.text = ''; this.pages = []; }
}
