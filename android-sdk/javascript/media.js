'use strict';
/** Optional passive host-state media. Invalid or absent artwork means fallback UI. */
function decodeMediaArtwork(art) {
  if (!art || !Number.isInteger(art.width) || !Number.isInteger(art.height) || art.width < 1 || art.height < 1 ||
      art.width > 64 || art.height > 64 || typeof art.gray4 !== 'string' || art.gray4.length !== art.width * art.height || !/^[0-9a-f]+$/.test(art.gray4)) return null;
  const pixels = new Uint8Array(art.gray4.length);
  for (let i = 0; i < pixels.length; i++) pixels[i] = parseInt(art.gray4[i], 16) * 17;
  return { width: art.width, height: art.height, pixels };
}
module.exports = { decodeMediaArtwork };
