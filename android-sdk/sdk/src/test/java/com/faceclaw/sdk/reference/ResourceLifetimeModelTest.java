package com.faceclaw.sdk.reference;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Adversarial, deterministic vectors for the contract's resource lifetime rules. */
public class ResourceLifetimeModelTest {
    private static ResourceLifetimeModel.ResourceMetadata resource(long id, String key) {
        return new ResourceLifetimeModel.ResourceMetadata(id, key, "image", 2, 2, 4);
    }

    @Test public void deduplicatedContentCountsBytesOnceAndReleaseIsIdempotent() {
        ResourceLifetimeModel model = new ResourceLifetimeModel();
        assertEquals(ResourceLifetimeModel.RegistrationStatus.REGISTERED,
                model.register(resource(1, "same-raster")).status);
        assertEquals(ResourceLifetimeModel.RegistrationStatus.REGISTERED,
                model.register(resource(2, "same-raster")).status);
        assertEquals(2, model.residentResourceCount());
        assertEquals(4, model.residentBytes());

        assertEquals(ResourceLifetimeModel.ReleaseStatus.RELEASED, model.release(1).status);
        assertEquals(ResourceLifetimeModel.ResourceState.DISPOSED, model.resource(1).state);
        assertEquals(1, model.residentResourceCount());
        assertEquals(4, model.residentBytes());
        assertEquals(ResourceLifetimeModel.ReleaseStatus.RELEASED, model.release(2).status);
        assertEquals(ResourceLifetimeModel.ReleaseStatus.DUPLICATE, model.release(2).status);
        assertEquals(Arrays.asList(1L, 2L), model.drainDisposals());
        assertTrue(model.drainDisposals().isEmpty());
    }

    @Test public void failedSceneCommitKeepsAcceptedSceneAndReferences() {
        ResourceLifetimeModel model = new ResourceLifetimeModel();
        model.register(resource(1, "old"));
        model.register(resource(2, "new"));
        long oldPending = model.beginScene("window", 1, Collections.singleton(1L)).pendingId;
        assertEquals(ResourceLifetimeModel.SceneTerminalStatus.ACCEPTED,
                model.acceptScene(oldPending).status);

        ResourceLifetimeModel.SceneStart replacement = model.beginScene("window", 2,
                Collections.singleton(2L));
        assertEquals(ResourceLifetimeModel.SceneStartStatus.PENDING, replacement.status);
        assertEquals(ResourceLifetimeModel.ReleaseStatus.DEFERRED, model.release(2).status);
        assertEquals(ResourceLifetimeModel.SceneTerminalStatus.REJECTED,
                model.rejectScene(replacement.pendingId, "host_rejected").status);

        ResourceLifetimeModel.SceneSnapshot accepted = model.acceptedScene("window");
        assertNotNull(accepted);
        assertEquals(1, accepted.version);
        assertEquals(Collections.singletonList(1L), accepted.resourceIds);
        assertEquals(ResourceLifetimeModel.ResourceState.OWNED, model.resource(1).state);
        assertEquals(ResourceLifetimeModel.ResourceState.DISPOSED, model.resource(2).state);
    }

    @Test public void releaseWhileAcceptedSceneAndPendingSceneRetainPixels() {
        ResourceLifetimeModel model = new ResourceLifetimeModel();
        model.register(resource(1, "accepted"));
        model.register(resource(2, "pending"));
        long first = model.beginScene("window", 1, Collections.singleton(1L)).pendingId;
        assertEquals(ResourceLifetimeModel.SceneTerminalStatus.ACCEPTED, model.acceptScene(first).status);
        assertEquals(ResourceLifetimeModel.ReleaseStatus.DEFERRED, model.release(1).status);
        assertEquals(ResourceLifetimeModel.ResourceState.RETAINED, model.resource(1).state);

        ResourceLifetimeModel.SceneStart pending = model.beginScene("window", 2,
                Collections.singleton(2L));
        assertEquals(ResourceLifetimeModel.ReleaseStatus.DEFERRED, model.release(2).status);
        assertEquals(ResourceLifetimeModel.ResourceState.RETAINED, model.resource(2).state);
        assertEquals(ResourceLifetimeModel.SceneTerminalStatus.REJECTED,
                model.rejectScene(pending.pendingId, "dropped").status);
        assertEquals(ResourceLifetimeModel.ResourceState.DISPOSED, model.resource(2).state);

        ResourceLifetimeModel.SceneStart clear = model.beginScene("window", 2, Collections.<Long>emptyList());
        assertEquals(ResourceLifetimeModel.SceneTerminalStatus.ACCEPTED,
                model.acceptScene(clear.pendingId).status);
        assertEquals(ResourceLifetimeModel.ResourceState.DISPOSED, model.resource(1).state);
        assertEquals(Arrays.asList(1L, 2L), model.drainDisposals());
    }

    @Test public void retainedFrameKeepsReleasedResourceAndSlotNeedsBufferReleased() {
        ResourceLifetimeModel model = new ResourceLifetimeModel();
        model.register(resource(1, "raster"));
        long scene = model.beginScene("window", 1, Collections.singleton(1L)).pendingId;
        model.acceptScene(scene);
        assertEquals(ResourceLifetimeModel.FrameStartStatus.IN_FLIGHT,
                model.beginFrame(10, "window", 1, 0, Collections.singleton(1L)).status);
        assertEquals(ResourceLifetimeModel.ReleaseStatus.DEFERRED, model.release(1).status);
        assertEquals(ResourceLifetimeModel.FrameTerminalStatus.DISPLAY_ACKED,
                model.finishFrame(10, ResourceLifetimeModel.FrameTerminalStatus.DISPLAY_ACKED).status);
        assertEquals(1, model.retainedFrameCount());
        assertEquals(ResourceLifetimeModel.ResourceState.RETAINED, model.resource(1).state);

        assertEquals(ResourceLifetimeModel.FrameStartStatus.REJECTED,
                model.beginFrame(11, "window", 1, 0, Collections.singleton(1L)).status);
        assertEquals(ResourceLifetimeModel.FrameTerminalStatus.BUFFER_RELEASED,
                model.bufferReleased(10).status);
        assertEquals(ResourceLifetimeModel.FrameStartStatus.IN_FLIGHT,
                model.beginFrame(11, "window", 1, 0, Collections.singleton(1L)).status);
        assertEquals(ResourceLifetimeModel.FrameTerminalStatus.BUFFER_RELEASED,
                model.finishFrame(11, ResourceLifetimeModel.FrameTerminalStatus.BUFFER_RELEASED).status);
        model.releaseRetainedFrame(10);
        assertEquals(ResourceLifetimeModel.ResourceState.RETAINED, model.resource(1).state);
        long clear = model.beginScene("window", 2, Collections.<Long>emptyList()).pendingId;
        assertEquals(ResourceLifetimeModel.SceneTerminalStatus.ACCEPTED, model.acceptScene(clear).status);
        assertEquals(ResourceLifetimeModel.ResourceState.DISPOSED, model.resource(1).state);
        assertEquals(Arrays.asList(1L), model.drainDisposals());
        assertFalse(model.bufferReleased(10).changed);
    }

    @Test public void reconnectDropsOneShotWorkAndReplaysOnlyLiveResourcesAndScenes() {
        ResourceLifetimeModel model = new ResourceLifetimeModel();
        model.register(resource(1, "live"));
        long scene = model.beginScene("window", 1, Collections.singleton(1L)).pendingId;
        model.acceptScene(scene);
        assertEquals(ResourceLifetimeModel.FrameStartStatus.IN_FLIGHT,
                model.beginFrame(20, "window", 1, 0, Collections.singleton(1L)).status);
        assertEquals(ResourceLifetimeModel.ReleaseStatus.DEFERRED, model.release(1).status);

        ResourceLifetimeModel.ReplayPlan replay = model.reconnect();
        assertEquals(ResourceLifetimeModel.ReplayMode.RESOURCE_REGISTRATION, replay.mode);
        assertEquals(1, replay.resources.size());
        assertEquals(1, replay.acceptedScenes.size());
        assertEquals(0, model.inFlightFrameCount());
        assertEquals(ResourceLifetimeModel.FrameTerminalStatus.UNKNOWN,
                model.finishFrame(20, ResourceLifetimeModel.FrameTerminalStatus.DROPPED).status);

        ResourceLifetimeModel.ReplayPlan repeated = model.reconnect();
        assertEquals(replay.resources, repeated.resources);
        assertEquals(replay.acceptedScenes.size(), repeated.acceptedScenes.size());
        assertTrue(model.drainDisposals().isEmpty());

        ResourceLifetimeModel.ReplayPlan fallback = model.replay(false);
        assertEquals(ResourceLifetimeModel.ReplayMode.RASTER_FALLBACK, fallback.mode);
        assertTrue(fallback.resources.isEmpty());
        assertEquals(1, fallback.fallbackResources.size());
    }

    @Test public void duplicateAndStaleSceneOrFrameCannotMutateReferences() {
        ResourceLifetimeModel model = new ResourceLifetimeModel();
        model.register(resource(1, "raster"));
        long pending = model.beginScene("window", 2, Collections.singleton(1L)).pendingId;
        assertEquals(ResourceLifetimeModel.SceneTerminalStatus.ACCEPTED, model.acceptScene(pending).status);
        assertEquals(ResourceLifetimeModel.SceneStartStatus.DUPLICATE,
                model.beginScene("window", 2, Collections.singleton(1L)).status);
        assertEquals(ResourceLifetimeModel.SceneStartStatus.STALE,
                model.beginScene("window", 1, Collections.<Long>emptyList()).status);

        assertEquals(ResourceLifetimeModel.FrameStartStatus.IN_FLIGHT,
                model.beginFrame(30, "window", 2, 1, Collections.singleton(1L)).status);
        assertEquals(ResourceLifetimeModel.FrameStartStatus.DUPLICATE,
                model.beginFrame(30, "window", 2, 2, Collections.singleton(1L)).status);
        assertEquals(ResourceLifetimeModel.FrameTerminalStatus.BUFFER_RELEASED,
                model.finishFrame(30, ResourceLifetimeModel.FrameTerminalStatus.BUFFER_RELEASED).status);
        assertEquals(ResourceLifetimeModel.FrameStartStatus.DUPLICATE,
                model.beginFrame(30, "window", 2, 1, Collections.singleton(1L)).status);
        assertEquals(1, model.resource(1).acceptedSceneRefs);
        assertEquals(0, model.resource(1).inFlightFrameRefs);
    }

    @Test public void resourceIdsAreMonotonicAndNeverReused() {
        ResourceLifetimeModel model = new ResourceLifetimeModel();
        assertEquals(ResourceLifetimeModel.RegistrationStatus.REGISTERED,
                model.register(resource(2, "two")).status);
        assertEquals(ResourceLifetimeModel.RegistrationStatus.CONFLICT,
                model.register(resource(1, "one")).status);
        assertEquals(ResourceLifetimeModel.RegistrationStatus.DUPLICATE,
                model.register(resource(2, "two")).status);
        assertEquals(ResourceLifetimeModel.RegistrationStatus.CONFLICT,
                model.register(resource(2, "different")).status);
        assertNull(model.resource(1));
    }

    @Test public void replayRecordDefersReleaseUntilCompletionAndCompletesOnce() {
        ResourceLifetimeModel model = new ResourceLifetimeModel();
        model.register(resource(1, "replay-raster"));
        ResourceLifetimeModel.ReplayPlan plan = model.replay(true);
        assertEquals(ResourceLifetimeModel.ReleaseStatus.DEFERRED, model.release(1).status);
        assertEquals(1, model.resource(1).replayRefs);
        model.completeReplay(plan);
        assertEquals(ResourceLifetimeModel.ResourceState.DISPOSED, model.resource(1).state);
        model.completeReplay(plan);
        assertEquals(Collections.singletonList(1L), model.drainDisposals());
    }
}
