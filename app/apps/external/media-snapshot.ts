import { mediaControllerBridge } from '../../native/media-controller';

/** Bounded passive artwork, from the same Android media session as the host music UI. */
let key = '';
let art: { width: number; height: number; gray4: string } | null = null;
let checkedAt = 0;
export function currentMediaSnapshot() {
  const media = mediaControllerBridge.snapshot();
  if (!media.available || !media.accessEnabled) { key = ''; art = null; return null; }
  const next = JSON.stringify([media.packageName, media.title, media.artist, media.album]);
  const now = Date.now();
  if (key !== next || now - checkedAt >= 5000) {
    key = next; checkedAt = now;
    const image = mediaControllerBridge.getAlbumArt(64);
    art = image && image.width <= 64 && image.height <= 64 ? {
      width: image.width, height: image.height,
      gray4: Array.from(image.pixels, value => Math.min(15, Math.round(value / 17)).toString(16)).join(''),
    } : null;
  }
  return { title: media.title.slice(0, 160), artist: media.artist.slice(0, 160), art };
}
