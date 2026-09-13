'use strict';
const { WindowMotion } = require('@faceclaw/motion');

// Inject the app's renderer; drawBody uses fixed final coordinates and card clipping.
module.exports = function animatedCard({ compact, expanded, buildBody, releaseBody, drawFrame, drawBody, invalidate }) {
  let opened = false, body = null, motion = null;
  const clearBody = () => { if (body !== null) releaseBody(body); body = null; };
  return {
    // Call from the SDK renderer using request.credit.targetPresentationTimeNanos.
    render(targetPresentationTimeNanos) {
      const frame = motion?.sample(targetPresentationTimeNanos / 1e6); if (!frame) return false;
      const canvas = drawFrame(frame.rect, 'EXAMPLE');
      if (frame.bodyVisible) {
        if (body === null) body = buildBody();
        drawBody(canvas, body, expanded(), frame.rect);
      }
      return { canvas, requestNextFrame: !frame.done };
    },
    setOpen(next) {
      if (next === opened) return;
      opened = next;
      if (!next) clearBody();
      const destination = next ? expanded() : compact();
      if (motion?.active) motion.retarget(destination, !next);
      else motion = new WindowMotion(next ? compact() : expanded(), destination, !next);
      invalidate();
    },
    // Call on hide, sleep, disconnect, removal, resize or content invalidation.
    cancel() { motion?.cancel(); motion = null; clearBody(); },
  };
};
