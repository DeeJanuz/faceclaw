import { windowLayoutPolicy, typographyPolicy } from "../extension-settings";
import { G2_LENS_WIDTH, GrayImage, type UiFont } from "../../graphics/image";
import { wrapText, truncateText } from "../../graphics/textwrap";
import { getDefaultSmallFont } from "../../graphics/ui-fonts";
import { Menu, type MenuDrawArgs } from "../menu-core";
import { listRowHeight, textInkBounds } from "../metrics";
import { MIN_WINDOW_HEIGHT, minWindowTop, appViewportRect } from "./geometry";

/**
 * The shared look of the shell's text dialogs (voice input, phone keyboard
 * input, and the assistant's reply): a solid box over whatever is on screen with a header
 * line (title, then status), the message so far, and either a menu of
 * destinations at the bottom or a gesture hint.
 */

const DIALOG_X = 40;
const DIALOG_W = G2_LENS_WIDTH - 80;
// The dialog fits inside the min-height window band (like the other shell
// overlays), wherever the vertical position setting puts it.
const DIALOG_MARGIN_Y = 28;
const DIALOG_H = MIN_WINDOW_HEIGHT - 2 * DIALOG_MARGIN_Y;
// Text is inset 16px into the dialog. Menu row boxes: 4px left of the text
// column, 2px shorter than the row pitch, with each row's label inset
// 8px / 4px into its box.
const MENU_ROW_GAP = 2;
const MENU_LABEL_INSET_X = 8;
const MENU_LABEL_INSET_Y = 4;
const HEADER_STATUS_GAP = 10;
const HINT_VALUE = 100;
const HINT_DEPTH = 2;

/** Dialog top edge; band-relative, so computed per paint. */
function dialogY(): number {
  return minWindowTop() + DIALOG_MARGIN_Y;
}

export type InputDialogRow = {
  label: string;
  /** Drawn faint: the row is not currently selectable (e.g. nothing to send). */
  dim: boolean;
};

export type InputDialogContent<T extends InputDialogRow = InputDialogRow> = {
  title: string;
  status: string;
  /** Gray level of the status line. Default 130. */
  statusValue?: number;
  /** The message body (a placeholder when nothing has been captured yet). */
  text: string;
  /**
   * Menu along the bottom edge (see createInputDialogMenu), or null for none.
   * Height is reserved for every item.
   */
  menu: Menu<T> | null;
  /** Gesture hint drawn along the bottom edge when there is no menu. */
  hint?: string;
};

/**
 * A wrapping menu of dialog rows, laid out and drawn the way paintInputDialog
 * expects. The owning layer keeps it across paints (so the selection sticks),
 * refreshes its rows with setItems, forwards scroll events to it, and hands
 * it to paintInputDialog while the menu is showing.
 */
export function createInputDialogMenu<T extends InputDialogRow>(items: readonly T[] = [], selectedIndex = 0): Menu<T> {
  return new Menu<T>({
    items,
    selectedIndex,
    wrap: true,
    rowGap: MENU_ROW_GAP,
    highlight: { radius: 6 },
    getHeight: () => listRowHeight(getDefaultSmallFont()),
    draw: drawMenuRow,
  });
}

function drawMenuRow({ image, item, x, y, width, selected }: MenuDrawArgs<InputDialogRow>): void {
  const value = item.dim ? 90 : selected ? 255 : 200;
  const font = getDefaultSmallFont();
  image.drawText(font, x + MENU_LABEL_INSET_X, y + MENU_LABEL_INSET_Y, truncateText(font, item.label, width - 2 * MENU_LABEL_INSET_X), value);
}

/** Paint the dialog onto `image` (an already-painted canvas of the layers below). */
export function paintInputDialog<T extends InputDialogRow>(image: GrayImage, content: InputDialogContent<T>): void {
  const font = getDefaultSmallFont();
  const menuRowH = listRowHeight(font);
  // A layout provider (ui.window-layout) can expand the dialog over the
  // medium window viewport, with roomier lines for reading.
  const expanded = windowLayoutPolicy().inputDialogs === "viewport";
  const viewport = appViewportRect("medium");
  const dialogX = expanded ? viewport.x + 8 : DIALOG_X;
  const dialogWidth = expanded ? viewport.width - 16 : DIALOG_W;
  const dialogHeight = expanded ? viewport.height - 16 : DIALOG_H;
  const textWidth = dialogWidth - 32;
  const top = expanded ? viewport.y + 8 : dialogY();
  const lineHeight = expanded ? font.lineHeight + 5 : 16;
  const style = typographyPolicy();
  if (expanded) image.bakeDeferredDrawsInPlace();

  // Solid dialog box over the underlying UI. Fill 1, not 0: identical after
  // 4bpp quantization, but 0 is transparent on the color-key shell surface.
  // Square unless a typography provider rounds it, matching the shell's other surfaces.
  image.fillRoundedRect(dialogX, top, dialogWidth, dialogHeight, 1, style.cardRadius ?? 0);
  image.drawRoundedRect(dialogX, top, dialogWidth, dialogHeight, 90, style.cardRadius ?? 0, style.borderWidth ?? (expanded ? 2 : 1));

  const left = dialogX + 16;
  const headerY = top + 12;
  let textTop: number;
  if (expanded) {
    // Title and status on lines of their own, each truncated to the dialog.
    image.drawText(font, left, headerY, truncateText(font, content.title, textWidth), 190);
    image.drawText(font, left, headerY + lineHeight, truncateText(font, content.status, textWidth), content.statusValue ?? 130);
    textTop = top + 20 + 2 * lineHeight;
  } else {
    // Title and status share one header line, the status in the space after the title.
    image.drawText(font, left, headerY, content.title, 220);
    const statusLeft = left + Math.ceil(font.measureText(content.title)) + HEADER_STATUS_GAP;
    const statusWidth = left + textWidth - statusLeft;
    image.drawText(font, statusLeft, headerY, truncateText(font, content.status, statusWidth), content.statusValue ?? 130);
    textTop = headerY + font.lineHeight + 12;
  }

  const menu = content.menu;
  const rowCount = menu?.items.length ?? 0;
  // Reserve space for the actual number of rows this menu has, or one row for the hint.
  const reservedRows = rowCount > 0 ? rowCount : content.hint ? 1 : 0;
  const textBottom = top + dialogHeight - reservedRows * menuRowH - 8;
  const maxLines = Math.max(1, ((textBottom - textTop) / lineHeight) | 0);

  // The tail of a long message stays in view: it is what was said last (or
  // where the phone keyboard is typing).
  const wrapped = wrapText(font, content.text, textWidth, { preserveLeadingWhitespace: expanded });
  const firstLine = Math.max(0, wrapped.length - maxLines);
  for (let index = firstLine; index < wrapped.length; index++) {
    image.drawText(font, left, textTop + (index - firstLine) * lineHeight, wrapped[index]!, expanded ? 190 : 235);
  }

  if (menu && rowCount > 0) {
    // Row boxes are one pitch apart, the last ending 6px above the dialog's bottom edge.
    const menuTop = top + dialogHeight - rowCount * menuRowH - 4;
    menu.paint(image, { x: left - 4, y: menuTop, width: dialogWidth - 24, height: rowCount * menuRowH - MENU_ROW_GAP }, true);
  } else if (content.hint) {
    // On the line the last menu row's label would occupy, floating in front of the dialog.
    drawDepthText(image, font, truncateText(font, content.hint, textWidth), left, top + dialogHeight - menuRowH, HINT_VALUE, HINT_DEPTH);
  }
}

/**
 * Draw one line of text at stereo depth: rendered into its own image (sized
 * to the font's ink, which can overshoot the line box) and replayed at depth.
 */
function drawDepthText(image: GrayImage, font: UiFont, text: string, x: number, y: number, value: number, depth: number): void {
  const ink = textInkBounds(font);
  const above = Math.max(0, -ink.top);
  const source = new GrayImage(Math.ceil(font.measureText(text)) + 2, above + Math.max(font.lineHeight, ink.bottom));
  source.drawText(font, 0, above, text, value);
  image.drawDepthImage(source, x, y - above, depth);
}
