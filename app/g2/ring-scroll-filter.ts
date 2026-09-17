/**
 * The ring firmware reports distance as a burst of discrete scroll events.
 * Keep that useful range, but cap how quickly repeated steps in one direction
 * reach the UI so an ordinary flick does not skip several rows.
 */
export const RING_SCROLL_REPEAT_INTERVAL_MS = 220;

export type RingScrollDirection = "up" | "down";

export class RingScrollRateLimiter {
  private lastDirection: RingScrollDirection | null = null;
  private lastAcceptedAtMs = Number.NEGATIVE_INFINITY;

  shouldDispatch(direction: RingScrollDirection, nowMs: number): boolean {
    if (
      direction !== this.lastDirection ||
      nowMs - this.lastAcceptedAtMs >= RING_SCROLL_REPEAT_INTERVAL_MS
    ) {
      this.lastDirection = direction;
      this.lastAcceptedAtMs = nowMs;
      return true;
    }
    return false;
  }

  reset(): void {
    this.lastDirection = null;
    this.lastAcceptedAtMs = Number.NEGATIVE_INFINITY;
  }
}
