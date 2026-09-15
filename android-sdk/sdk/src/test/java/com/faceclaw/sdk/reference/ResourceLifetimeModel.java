package com.faceclaw.sdk.reference;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure reference state machine for the resource/scene lifetime contract.
 *
 * <p>This class intentionally lives in unit-test sources. It has no Android,
 * Binder, transport, compositor, or pixel-memory dependency. P22 can use the
 * executable transitions here as a reference while wiring the real host.</p>
 */
public final class ResourceLifetimeModel {
    public static final int DEFAULT_MAX_PENDING_SCENES = 32;

    public enum ResourceState { OWNED, RETAINED, DISPOSED }

    public enum RegistrationStatus { REGISTERED, DUPLICATE, CONFLICT, INVALID }

    /** Mirrors the contract's resource.release result, with DUPLICATE for a repeated local request. */
    public enum ReleaseStatus { RELEASED, DEFERRED, DUPLICATE, UNKNOWN }

    public enum SceneStartStatus { PENDING, DUPLICATE, STALE, REJECTED, PENDING_LIMIT }

    public enum SceneTerminalStatus { ACCEPTED, REJECTED, DUPLICATE, UNKNOWN }

    public enum FrameStartStatus { IN_FLIGHT, DUPLICATE, REJECTED }

    /** BUFFER_RELEASED is the only outcome that permits a slot to be reused. */
    public enum FrameTerminalStatus {
        BUFFER_RELEASED,
        DISPLAY_ACKED,
        PREVIEW_COMMITTED,
        DROPPED,
        UNKNOWN
    }

    public enum ReplayMode { RESOURCE_REGISTRATION, RASTER_FALLBACK }

    public static final class ResourceMetadata {
        public final long id;
        public final String contentKey;
        public final String type;
        public final int width;
        public final int height;
        public final long byteSize;

        public ResourceMetadata(long id, String contentKey, String type, int width, int height, long byteSize) {
            if (id <= 0 || contentKey == null || contentKey.isEmpty() || type == null || type.isEmpty()
                    || width <= 0 || height <= 0 || byteSize <= 0) {
                throw new IllegalArgumentException("Invalid resource metadata");
            }
            this.id = id;
            this.contentKey = contentKey;
            this.type = type;
            this.width = width;
            this.height = height;
            this.byteSize = byteSize;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof ResourceMetadata)) return false;
            ResourceMetadata value = (ResourceMetadata) other;
            return id == value.id && width == value.width && height == value.height
                    && byteSize == value.byteSize && contentKey.equals(value.contentKey)
                    && type.equals(value.type);
        }

        @Override public int hashCode() {
            int result = Long.hashCode(id);
            result = 31 * result + contentKey.hashCode();
            result = 31 * result + type.hashCode();
            result = 31 * result + width;
            result = 31 * result + height;
            result = 31 * result + Long.hashCode(byteSize);
            return result;
        }
    }

    public static final class ResourceSnapshot {
        public final ResourceMetadata metadata;
        public final ResourceState state;
        public final int acceptedSceneRefs;
        public final int pendingSceneRefs;
        public final int retainedFrameRefs;
        public final int inFlightFrameRefs;
        public final int replayRefs;

        private ResourceSnapshot(ResourceRecord resource) {
            metadata = resource.metadata;
            state = resource.state();
            acceptedSceneRefs = resource.acceptedSceneRefs;
            pendingSceneRefs = resource.pendingSceneRefs;
            retainedFrameRefs = resource.retainedFrameRefs;
            inFlightFrameRefs = resource.inFlightFrameRefs;
            replayRefs = resource.replayRefs;
        }
    }

    public static final class RegistrationResult {
        public final RegistrationStatus status;
        public final ResourceSnapshot resource;

        private RegistrationResult(RegistrationStatus status, ResourceSnapshot resource) {
            this.status = status;
            this.resource = resource;
        }
    }

    public static final class ReleaseResult {
        public final ReleaseStatus status;
        public final ResourceSnapshot resource;
        public final boolean disposalIssued;

        private ReleaseResult(ReleaseStatus status, ResourceSnapshot resource, boolean disposalIssued) {
            this.status = status;
            this.resource = resource;
            this.disposalIssued = disposalIssued;
        }
    }

    public static final class SceneStart {
        public final SceneStartStatus status;
        public final long pendingId;
        public final String surfaceId;
        public final long version;

        private SceneStart(SceneStartStatus status, long pendingId, String surfaceId, long version) {
            this.status = status;
            this.pendingId = pendingId;
            this.surfaceId = surfaceId;
            this.version = version;
        }
    }

    public static final class SceneResult {
        public final SceneTerminalStatus status;
        public final String reason;
        public final String surfaceId;
        public final long version;

        private SceneResult(SceneTerminalStatus status, String reason, String surfaceId, long version) {
            this.status = status;
            this.reason = reason;
            this.surfaceId = surfaceId;
            this.version = version;
        }
    }

    public static final class SceneSnapshot {
        public final String surfaceId;
        public final long version;
        public final List<Long> resourceIds;

        private SceneSnapshot(Scene scene) {
            surfaceId = scene.surfaceId;
            version = scene.version;
            resourceIds = immutableIds(scene.resourceIds);
        }
    }

    public static final class FrameStart {
        public final FrameStartStatus status;
        public final long frameId;
        public final String surfaceId;
        public final int slotId;

        private FrameStart(FrameStartStatus status, long frameId, String surfaceId, int slotId) {
            this.status = status;
            this.frameId = frameId;
            this.surfaceId = surfaceId;
            this.slotId = slotId;
        }
    }

    public static final class FrameResult {
        public final boolean changed;
        public final FrameTerminalStatus status;
        public final boolean slotReusable;
        public final String surfaceId;
        public final int slotId;

        private FrameResult(boolean changed, FrameTerminalStatus status, boolean slotReusable,
                String surfaceId, int slotId) {
            this.changed = changed;
            this.status = status;
            this.slotReusable = slotReusable;
            this.surfaceId = surfaceId;
            this.slotId = slotId;
        }
    }

    public static final class ReplayPlan {
        public final ReplayMode mode;
        public final long replayId;
        public final List<ResourceMetadata> resources;
        public final List<ResourceMetadata> fallbackResources;
        public final List<SceneSnapshot> acceptedScenes;

        private ReplayPlan(ReplayMode mode, long replayId, List<ResourceMetadata> resources,
                List<ResourceMetadata> fallbackResources, List<SceneSnapshot> acceptedScenes) {
            this.mode = mode;
            this.replayId = replayId;
            this.resources = Collections.unmodifiableList(new ArrayList<>(resources));
            this.fallbackResources = Collections.unmodifiableList(new ArrayList<>(fallbackResources));
            this.acceptedScenes = Collections.unmodifiableList(new ArrayList<>(acceptedScenes));
        }
    }

    private static final class Allocation {
        final String contentKey;
        final long byteSize;
        final Set<Long> handles = new TreeSet<>();

        Allocation(String contentKey, long byteSize) {
            this.contentKey = contentKey;
            this.byteSize = byteSize;
        }
    }

    private static final class ResourceRecord {
        final ResourceMetadata metadata;
        final Allocation allocation;
        boolean appOwned = true;
        boolean disposed;
        int acceptedSceneRefs;
        int pendingSceneRefs;
        int retainedFrameRefs;
        int inFlightFrameRefs;
        int replayRefs;

        ResourceRecord(ResourceMetadata metadata, Allocation allocation) {
            this.metadata = metadata;
            this.allocation = allocation;
        }

        boolean referenced() {
            return acceptedSceneRefs > 0 || pendingSceneRefs > 0
                    || retainedFrameRefs > 0 || inFlightFrameRefs > 0 || replayRefs > 0;
        }

        boolean live() { return !disposed; }

        ResourceState state() {
            if (disposed) return ResourceState.DISPOSED;
            return appOwned ? ResourceState.OWNED : ResourceState.RETAINED;
        }
    }

    private static final class Scene {
        final String surfaceId;
        final long version;
        final Set<Long> resourceIds;

        Scene(String surfaceId, long version, Collection<Long> resourceIds) {
            this.surfaceId = surfaceId;
            this.version = version;
            this.resourceIds = canonicalIds(resourceIds);
        }
    }

    private static final class PendingScene {
        final long id;
        final Scene scene;

        PendingScene(long id, Scene scene) {
            this.id = id;
            this.scene = scene;
        }
    }

    private static final class Frame {
        final long frameId;
        final String surfaceId;
        final int slotId;
        final Set<Long> resourceIds;
        FrameTerminalStatus status;
        boolean slotReleased;

        Frame(long frameId, String surfaceId, int slotId, Collection<Long> resourceIds) {
            this.frameId = frameId;
            this.surfaceId = surfaceId;
            this.slotId = slotId;
            this.resourceIds = canonicalIds(resourceIds);
        }
    }

    private final Map<Long, ResourceRecord> resources = new TreeMap<>();
    private final Map<String, Allocation> allocations = new HashMap<>();
    private final Set<Long> usedResourceIds = new HashSet<>();
    private final Map<String, Scene> acceptedScenes = new TreeMap<>();
    private final Map<Long, PendingScene> pendingScenes = new TreeMap<>();
    private final Map<Long, Frame> frames = new TreeMap<>();
    private final Map<Long, FrameTerminalStatus> terminalFrames = new TreeMap<>();
    private final Map<String, Long> retainedFrameBySurface = new TreeMap<>();
    private final Map<Long, Frame> retainedFrames = new TreeMap<>();
    private final Map<Long, Set<Long>> replayRecords = new TreeMap<>();
    private final Set<Long> disposedSinceLastRead = new TreeSet<>();
    private final int maxPendingScenes;
    private long highestResourceId;
    private long nextPendingId = 1;
    private long nextReplayId = 1;

    public ResourceLifetimeModel() { this(DEFAULT_MAX_PENDING_SCENES); }

    public ResourceLifetimeModel(int maxPendingScenes) {
        if (maxPendingScenes < 1) throw new IllegalArgumentException("Invalid pending scene limit");
        this.maxPendingScenes = maxPendingScenes;
    }

    public synchronized RegistrationResult register(ResourceMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        ResourceRecord existing = resources.get(metadata.id);
        if (existing != null) {
            RegistrationStatus status = existing.metadata.equals(metadata)
                    ? RegistrationStatus.DUPLICATE : RegistrationStatus.CONFLICT;
            return new RegistrationResult(status, new ResourceSnapshot(existing));
        }
        if (usedResourceIds.contains(metadata.id) || metadata.id <= highestResourceId) {
            return new RegistrationResult(RegistrationStatus.CONFLICT, null);
        }
        Allocation allocation = allocations.get(metadata.contentKey);
        if (allocation == null) {
            allocation = new Allocation(metadata.contentKey, metadata.byteSize);
            allocations.put(metadata.contentKey, allocation);
        } else if (allocation.byteSize != metadata.byteSize) {
            return new RegistrationResult(RegistrationStatus.INVALID, null);
        }
        usedResourceIds.add(metadata.id);
        highestResourceId = metadata.id;
        ResourceRecord resource = new ResourceRecord(metadata, allocation);
        resources.put(metadata.id, resource);
        allocation.handles.add(metadata.id);
        return new RegistrationResult(RegistrationStatus.REGISTERED, new ResourceSnapshot(resource));
    }

    public synchronized ReleaseResult release(long resourceId) {
        ResourceRecord resource = resources.get(resourceId);
        if (resource == null) return new ReleaseResult(ReleaseStatus.UNKNOWN, null, false);
        if (!resource.appOwned) {
            return new ReleaseResult(ReleaseStatus.DUPLICATE, new ResourceSnapshot(resource), false);
        }
        resource.appOwned = false;
        boolean disposed = disposeIfUnreferenced(resource);
        return new ReleaseResult(disposed ? ReleaseStatus.RELEASED : ReleaseStatus.DEFERRED,
                new ResourceSnapshot(resource), disposed);
    }

    public synchronized SceneStart beginScene(String surfaceId, long version, Collection<Long> resourceIds) {
        if (surfaceId == null || surfaceId.isEmpty() || version <= 0) {
            return new SceneStart(SceneStartStatus.REJECTED, 0, surfaceId, version);
        }
        Scene candidate;
        try {
            candidate = new Scene(surfaceId, version, resourceIds);
        } catch (RuntimeException invalid) {
            return new SceneStart(SceneStartStatus.REJECTED, 0, surfaceId, version);
        }
        Scene current = acceptedScenes.get(surfaceId);
        if (current != null && version <= current.version) {
            if (version == current.version && current.resourceIds.equals(candidate.resourceIds)) {
                return new SceneStart(SceneStartStatus.DUPLICATE, 0, surfaceId, version);
            }
            return new SceneStart(SceneStartStatus.STALE, 0, surfaceId, version);
        }
        if (pendingScenes.size() >= maxPendingScenes) {
            return new SceneStart(SceneStartStatus.PENDING_LIMIT, 0, surfaceId, version);
        }
        for (Long id : candidate.resourceIds) {
            ResourceRecord resource = resources.get(id);
            if (resource == null || !resource.live()) {
                return new SceneStart(SceneStartStatus.REJECTED, 0, surfaceId, version);
            }
        }
        long id = nextPendingId++;
        PendingScene pending = new PendingScene(id, candidate);
        pendingScenes.put(id, pending);
        for (Long resourceId : candidate.resourceIds) resources.get(resourceId).pendingSceneRefs++;
        return new SceneStart(SceneStartStatus.PENDING, id, surfaceId, version);
    }

    public synchronized SceneResult acceptScene(long pendingId) {
        PendingScene pending = pendingScenes.remove(pendingId);
        if (pending == null) return new SceneResult(SceneTerminalStatus.UNKNOWN, "unknown_pending", "", 0);
        Scene current = acceptedScenes.get(pending.scene.surfaceId);
        if (current != null && pending.scene.version <= current.version) {
            dropPendingRefs(pending.scene);
            return new SceneResult(SceneTerminalStatus.DUPLICATE, "stale_version",
                    pending.scene.surfaceId, pending.scene.version);
        }
        for (Long resourceId : pending.scene.resourceIds) {
            ResourceRecord resource = resources.get(resourceId);
            if (resource == null || !resource.live()) {
                dropPendingRefs(pending.scene);
                return new SceneResult(SceneTerminalStatus.REJECTED, "resource_unavailable",
                        pending.scene.surfaceId, pending.scene.version);
            }
        }
        for (Long resourceId : pending.scene.resourceIds) {
            ResourceRecord resource = resources.get(resourceId);
            resource.pendingSceneRefs--;
            resource.acceptedSceneRefs++;
        }
        if (current != null) {
            for (Long resourceId : current.resourceIds) {
                ResourceRecord resource = resources.get(resourceId);
                resource.acceptedSceneRefs--;
                disposeIfUnreferenced(resource);
            }
        }
        acceptedScenes.put(pending.scene.surfaceId, pending.scene);
        return new SceneResult(SceneTerminalStatus.ACCEPTED, "", pending.scene.surfaceId, pending.scene.version);
    }

    public synchronized SceneResult rejectScene(long pendingId, String reason) {
        PendingScene pending = pendingScenes.remove(pendingId);
        if (pending == null) return new SceneResult(SceneTerminalStatus.UNKNOWN, "unknown_pending", "", 0);
        dropPendingRefs(pending.scene);
        return new SceneResult(SceneTerminalStatus.REJECTED,
                reason == null || reason.isEmpty() ? "rejected" : reason,
                pending.scene.surfaceId, pending.scene.version);
    }

    public synchronized SceneSnapshot acceptedScene(String surfaceId) {
        Scene scene = acceptedScenes.get(surfaceId);
        return scene == null ? null : new SceneSnapshot(scene);
    }

    public synchronized FrameStart beginFrame(long frameId, String surfaceId, long sceneVersion,
            int slotId, Collection<Long> resourceIds) {
        if (frameId <= 0 || surfaceId == null || surfaceId.isEmpty() || sceneVersion <= 0 || slotId < 0) {
            return new FrameStart(FrameStartStatus.REJECTED, frameId, surfaceId, slotId);
        }
        if (frames.containsKey(frameId) || terminalFrames.containsKey(frameId)) {
            return new FrameStart(FrameStartStatus.DUPLICATE, frameId, surfaceId, slotId);
        }
        Scene scene = acceptedScenes.get(surfaceId);
        if (scene == null || scene.version != sceneVersion) {
            return new FrameStart(FrameStartStatus.REJECTED, frameId, surfaceId, slotId);
        }
        Set<Long> ids;
        try {
            ids = canonicalIds(resourceIds);
        } catch (RuntimeException invalid) {
            return new FrameStart(FrameStartStatus.REJECTED, frameId, surfaceId, slotId);
        }
        for (Long resourceId : ids) {
            ResourceRecord resource = resources.get(resourceId);
            if (resource == null || !resource.live()) {
                return new FrameStart(FrameStartStatus.REJECTED, frameId, surfaceId, slotId);
            }
        }
        for (Frame frame : frames.values()) {
            if (frame.surfaceId.equals(surfaceId) && frame.slotId == slotId) {
                return new FrameStart(FrameStartStatus.REJECTED, frameId, surfaceId, slotId);
            }
        }
        Frame frame = new Frame(frameId, surfaceId, slotId, ids);
        frames.put(frameId, frame);
        for (Long resourceId : ids) resources.get(resourceId).inFlightFrameRefs++;
        return new FrameStart(FrameStartStatus.IN_FLIGHT, frameId, surfaceId, slotId);
    }

    public synchronized FrameResult finishFrame(long frameId, FrameTerminalStatus status) {
        if (status == null) throw new IllegalArgumentException("Missing frame outcome");
        Frame frame = frames.get(frameId);
        if (frame == null) {
            FrameTerminalStatus previous = terminalFrames.get(frameId);
            if (previous != null) return new FrameResult(false, previous, previous == FrameTerminalStatus.BUFFER_RELEASED, "", -1);
            return new FrameResult(false, status, false, "", -1);
        }
        if (frame.status != null) {
            return new FrameResult(false, frame.status, frame.slotReleased, frame.surfaceId, frame.slotId);
        }
        frame.status = status;
        releaseInFlightRefs(frame);
        if (status == FrameTerminalStatus.DISPLAY_ACKED || status == FrameTerminalStatus.PREVIEW_COMMITTED) {
            replaceRetainedFrame(frame);
        }
        if (status == FrameTerminalStatus.BUFFER_RELEASED) {
            frame.slotReleased = true;
            frames.remove(frameId);
            terminalFrames.put(frameId, status);
        }
        return new FrameResult(true, status, frame.slotReleased, frame.surfaceId, frame.slotId);
    }

    /** Releases a slot after its display outcome; only this transition permits slot reuse. */
    public synchronized FrameResult bufferReleased(long frameId) {
        Frame frame = frames.get(frameId);
        if (frame == null) {
            FrameTerminalStatus previous = terminalFrames.get(frameId);
            if (previous != null) return new FrameResult(false, previous, true, "", -1);
            return new FrameResult(false, FrameTerminalStatus.BUFFER_RELEASED, false, "", -1);
        }
        if (frame.status == null) {
            return new FrameResult(false, FrameTerminalStatus.BUFFER_RELEASED, false, frame.surfaceId, frame.slotId);
        }
        if (frame.slotReleased) {
            return new FrameResult(false, FrameTerminalStatus.BUFFER_RELEASED, true, frame.surfaceId, frame.slotId);
        }
        frame.slotReleased = true;
        frames.remove(frameId);
        terminalFrames.put(frameId, FrameTerminalStatus.BUFFER_RELEASED);
        return new FrameResult(true, FrameTerminalStatus.BUFFER_RELEASED, true, frame.surfaceId, frame.slotId);
    }

    public synchronized void releaseRetainedFrame(long frameId) {
        String surface = null;
        for (Map.Entry<String, Long> entry : retainedFrameBySurface.entrySet()) {
            if (entry.getValue() == frameId) {
                surface = entry.getKey();
                break;
            }
        }
        if (surface == null) return;
        retainedFrameBySurface.remove(surface);
        Frame frame = terminalFrameRecord(frameId);
        retainedFrames.remove(frameId);
        if (frame != null) {
            for (Long resourceId : frame.resourceIds) {
                ResourceRecord resource = resources.get(resourceId);
                if (resource != null) {
                    resource.retainedFrameRefs--;
                    disposeIfUnreferenced(resource);
                }
            }
        }
    }

    public synchronized ReplayPlan reconnect() {
        // Pending scene submissions and frame commands are one-shot. They are not replayed.
        for (PendingScene pending : new ArrayList<>(pendingScenes.values())) {
            rejectScene(pending.id, "session_replaced");
        }
        for (Frame frame : new ArrayList<>(frames.values())) {
            if (frame.status == null) finishFrame(frame.frameId, FrameTerminalStatus.UNKNOWN);
        }
        // A retained raster belongs to the old host. Accepted scene references remain authoritative.
        for (Long frameId : new ArrayList<>(retainedFrameBySurface.values())) releaseRetainedFrame(frameId);
        // Replay records belong to the replaced host. Drop their references before creating
        // the new plan so an old plan cannot pin a released resource forever.
        for (Long replayId : new ArrayList<>(replayRecords.keySet())) completeReplayRecord(replayId);
        return replay(true);
    }

    public synchronized ReplayPlan replay(boolean resourceReleaseSupported) {
        List<ResourceMetadata> live = new ArrayList<>();
        Set<Long> replayIds = new TreeSet<>();
        for (ResourceRecord resource : resources.values()) {
            if (resource.live()) {
                live.add(resource.metadata);
                replayIds.add(resource.metadata.id);
            }
        }
        long replayId = nextReplayId++;
        replayRecords.put(replayId, replayIds);
        for (Long resourceId : replayIds) resources.get(resourceId).replayRefs++;
        List<SceneSnapshot> scenes = new ArrayList<>();
        for (Scene scene : acceptedScenes.values()) scenes.add(new SceneSnapshot(scene));
        if (resourceReleaseSupported) {
            return new ReplayPlan(ReplayMode.RESOURCE_REGISTRATION, replayId, live,
                    Collections.<ResourceMetadata>emptyList(), scenes);
        }
        // Old peers must use an explicit raster fallback. The model exposes the metadata that
        // the app-side fallback must materialize; it never silently drops a live resource.
        return new ReplayPlan(ReplayMode.RASTER_FALLBACK, replayId,
                Collections.<ResourceMetadata>emptyList(), live, scenes);
    }

    /** Completes one replay record. Repeating completion is a no-op. */
    public synchronized void completeReplay(ReplayPlan plan) {
        if (plan == null) return;
        completeReplayRecord(plan.replayId);
    }

    private void completeReplayRecord(long replayId) {
        Set<Long> replayIds = replayRecords.remove(replayId);
        if (replayIds == null) return;
        for (Long resourceId : replayIds) {
            ResourceRecord resource = resources.get(resourceId);
            if (resource != null && resource.replayRefs > 0) {
                resource.replayRefs--;
                disposeIfUnreferenced(resource);
            }
        }
    }

    public synchronized ResourceSnapshot resource(long resourceId) {
        ResourceRecord resource = resources.get(resourceId);
        return resource == null ? null : new ResourceSnapshot(resource);
    }

    public synchronized List<Long> drainDisposals() {
        List<Long> result = new ArrayList<>(disposedSinceLastRead);
        disposedSinceLastRead.clear();
        return result;
    }

    public synchronized long residentBytes() {
        long result = 0;
        for (Allocation allocation : allocations.values()) {
            boolean resident = false;
            for (Long resourceId : allocation.handles) {
                ResourceRecord resource = resources.get(resourceId);
                if (resource != null && resource.live()) { resident = true; break; }
            }
            if (resident) result += allocation.byteSize;
        }
        return result;
    }

    public synchronized int residentResourceCount() {
        int result = 0;
        for (ResourceRecord resource : resources.values()) if (resource.live()) result++;
        return result;
    }

    public synchronized int pendingSceneCount() { return pendingScenes.size(); }

    public synchronized int inFlightFrameCount() {
        int result = 0;
        for (Frame frame : frames.values()) if (frame.status == null) result++;
        return result;
    }

    public synchronized int retainedFrameCount() { return retainedFrameBySurface.size(); }

    private void dropPendingRefs(Scene scene) {
        for (Long resourceId : scene.resourceIds) {
            ResourceRecord resource = resources.get(resourceId);
            if (resource != null) {
                resource.pendingSceneRefs--;
                disposeIfUnreferenced(resource);
            }
        }
    }

    private boolean disposeIfUnreferenced(ResourceRecord resource) {
        if (resource.appOwned || resource.referenced() || resource.disposed) return false;
        resource.disposed = true;
        disposedSinceLastRead.add(resource.metadata.id);
        return true;
    }

    private void releaseInFlightRefs(Frame frame) {
        for (Long resourceId : frame.resourceIds) {
            ResourceRecord resource = resources.get(resourceId);
            if (resource != null && resource.inFlightFrameRefs > 0) {
                resource.inFlightFrameRefs--;
                disposeIfUnreferenced(resource);
            }
        }
    }

    private void replaceRetainedFrame(Frame frame) {
        Long oldId = retainedFrameBySurface.put(frame.surfaceId, frame.frameId);
        retainedFrames.put(frame.frameId, frame);
        if (oldId == null || oldId == frame.frameId) {
            for (Long resourceId : frame.resourceIds) resources.get(resourceId).retainedFrameRefs++;
            return;
        }
        Frame old = retainedFrames.get(oldId);
        if (old != null) {
            for (Long resourceId : old.resourceIds) {
                ResourceRecord resource = resources.get(resourceId);
                if (resource != null && resource.retainedFrameRefs > 0) {
                    resource.retainedFrameRefs--;
                    disposeIfUnreferenced(resource);
                }
            }
        }
        for (Long resourceId : frame.resourceIds) resources.get(resourceId).retainedFrameRefs++;
    }

    private Frame terminalFrameRecord(long frameId) {
        return retainedFrames.get(frameId);
    }

    private static Set<Long> canonicalIds(Collection<Long> values) {
        if (values == null) throw new IllegalArgumentException("Missing resource IDs");
        TreeSet<Long> result = new TreeSet<>();
        for (Long value : values) {
            if (value == null || value <= 0) throw new IllegalArgumentException("Invalid resource ID");
            result.add(value);
        }
        return result;
    }

    private static List<Long> immutableIds(Collection<Long> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
