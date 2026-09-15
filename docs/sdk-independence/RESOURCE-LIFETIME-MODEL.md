# Resource lifetime reference model

This note freezes the P21 reference behavior from contract v1. The executable
model is test-only at
`android-sdk/sdk/src/test/java/com/faceclaw/sdk/reference/ResourceLifetimeModel.java`.
It has no Android or Binder dependency and is deliberately separate from the
host's real resource storage. P22 should wire the host to these transitions and
run the same adversarial cases against the real compositor boundary.

## Ownership and resident bytes

Resource IDs are session-scoped, strictly increasing, and never reused. A
duplicate registration with the same immutable metadata is idempotent. A
different body for an existing ID is a conflict. Each ID owns an application
reference until `release` succeeds locally.

Resources with the same immutable `contentKey` share one resident allocation.
`residentBytes` sums each live allocation once, while `residentResourceCount`
counts live handles. Releasing one of two handles for the same content therefore
does not reclaim the allocation or make the other handle unavailable.

The state transitions are:

| State | Meaning | Disposal allowed |
| --- | --- | --- |
| `OWNED` | Application ownership is present. | Only after release and no references. |
| `RETAINED` | Ownership was released, but an accepted scene, pending scene, retained raster frame, or in-flight frame still references the resource. | When the last reference drops. |
| `DISPOSED` | The model emitted exactly one disposal transition. | Never; the ID cannot be registered again. |

`release` is idempotent. It returns `DEFERRED` while references remain and
`RELEASED` when disposal is issued. `drainDisposals()` is an explicit test seam
for asserting that no later duplicate operation emits another disposal.

## Scene commits

`beginScene` validates all resource IDs and records a pending scene reference.
`acceptScene` swaps the accepted scene atomically. A failed or rejected commit
drops only its pending references; the previous accepted scene and its
references remain unchanged. Replacing an accepted scene drops the old scene's
references only after the new scene is ready.

Pending scenes are bounded. They are one-shot work and are rejected on
reconnect; pending scene messages are never replayed.

## Frames and slot reuse

An in-flight frame increments resource references and reserves a surface slot.
Terminal outcomes release the in-flight references exactly once. A display or
preview outcome also creates one retained raster record for that surface; the
next retained frame replaces it and releases the old retained references.

`BUFFER_RELEASED` is the slot lifecycle boundary. A slot remains unavailable
after `DISPLAY_ACKED`, `PREVIEW_COMMITTED`, `DROPPED`, or `UNKNOWN` until
`bufferReleased` occurs. Reusing a slot earlier is rejected. Frame IDs are
idempotent and cannot be reused after a terminal result.

Retained raster records contain metadata and resource IDs, never pixel data in
the reference model. Releasing a retained record is separate from releasing
the buffer slot, so either operation can be tested without accidentally
dropping the other reference.

## Reconnect and fallback

Reconnect rejects pending scenes, marks in-flight frames `UNKNOWN`, and drops
retained raster records owned by the old host. It returns a plan containing
only still-live resource metadata and accepted scene state. No frame or pending
scene command is replayed, and calling reconnect again does not emit another
terminal transition.

When resource-release support is unavailable, `replay(false)` returns an
explicit `RASTER_FALLBACK` plan containing every live resource's metadata. It
does not silently omit the resource or reinterpret it as a different wire
operation. The app-side fallback must materialize the retained raster from its
own cache; this pure model intentionally does not carry pixel bytes.

