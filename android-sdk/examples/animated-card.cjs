'use strict';
const { WindowMotion, DURATION_MS } = require('@faceclaw/motion');

// Inject the app's renderer; drawBody uses fixed final coordinates and card clipping.
module.exports = function animatedCard({ compact, expanded, buildBody, releaseBody, drawFrame, drawBody, invalidate }) {
  let opened = false, body = null, motion = null, suspended = false, neutralHeading = false;
  const clearBody = () => { if (body !== null) releaseBody(body); body = null; };
  return {
    // Call from the SDK renderer using request.credit.targetPresentationTimeNanos.
    render(targetPresentationTimeNanos) {
      if (suspended) return false;
      let frame = motion?.active ? motion.sample(targetPresentationTimeNanos / 1e6) : null;
      if (!frame) {
        const target = opened ? expanded() : compact();
        const settled = new WindowMotion(target, target, !opened);
        settled.sample(0); frame = settled.sample(DURATION_MS);
      }
      const canvas = drawFrame(frame.rect, neutralHeading ? '' : 'EXAMPLE');
      if (frame.bodyVisible) {
        if (body === null) body = buildBody();
        drawBody(canvas, body, expanded(), frame.rect);
      }
      if (frame.done) motion = null;
      return { canvas, requestNextFrame: !frame.done };
    },
    setOpen(next) {
      if (next === opened) return;
      opened = next;
      neutralHeading = false;
      if (!next) clearBody();
      if (suspended) return;
      const destination = next ? expanded() : compact();
      if (motion?.active) motion.retarget(destination, !next);
      else motion = new WindowMotion(next ? compact() : expanded(), destination, !next);
      invalidate();
    },
    // Caller compares relevant values before invoking this. Do not reset motion.
    ambientChanged() { if (!suspended) invalidate(); },
    // buildBody must read the CURRENT authorized model after invalidation.
    invalidateContent() {
      clearBody();
      if (motion?.closing) neutralHeading = true;
      if (!suspended) invalidate();
    },
    // Real surface/lifecycle loss suspends rendering, unlike content refresh.
    cancel() { suspended = true; motion?.cancel(); motion = null; clearBody(); },
    // Restore the desired settled state using the new surface/geometry.
    resume() { suspended = false; motion = null; invalidate(); },
  };
};
