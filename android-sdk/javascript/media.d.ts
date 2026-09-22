/** Optional host-state media; null means unavailable, hidden, or not granted. */
export type HostMedia = { title: string; artist: string; art: MediaArtwork | null };
export type MediaArtwork = { width: number; height: number; gray4: string };
export function decodeMediaArtwork(art: unknown): { width: number; height: number; pixels: Uint8Array } | null;
