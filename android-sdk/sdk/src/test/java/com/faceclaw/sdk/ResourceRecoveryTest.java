package com.faceclaw.sdk;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.*;

public class ResourceRecoveryTest {
    @Test
    public void churnReclaimsQuotaWithoutReconnectingOrReusingIds() {
        List<Integer> releases = new ArrayList<>();
        ResourceRegistry registry =
                new ResourceRegistry(
                        (h, p) -> {},
                        id -> {
                            releases.add(id);
                            return true;
                        },
                        () -> true,
                        () -> false,
                        Collections::emptySet,
                        () -> {});
        int last = 0;
        for (int i = 0; i < 5000; i++) {
            ResourceHandle handle =
                    registry.registerGrayImage(
                            2, 1, ByteBuffer.wrap(new byte[] {(byte) i, (byte) (i >> 8)}));
            assertTrue(handle.id > last);
            last = handle.id;
            handle.close();
            assertEquals(handle.id, (int) releases.get(i));
            registry.released(handle.id, "released");
            assertEquals(0, registry.residentBytes());
            assertEquals(0, registry.residentCount());
        }
    }

    @Test
    public void retainedSceneAndWriterPreventReleaseUntilTheirReferencesEnd() {
        Set<Integer> refs = new HashSet<>();
        boolean[] writing = {true};
        List<Integer> released = new ArrayList<>();
        ResourceRegistry registry =
                new ResourceRegistry(
                        (h, p) -> {},
                        id -> {
                            released.add(id);
                            return true;
                        },
                        () -> true,
                        () -> writing[0],
                        () -> refs,
                        () -> {});
        ResourceHandle handle = registry.registerGrayImage(1, 1, ByteBuffer.wrap(new byte[] {7}));
        refs.add(handle.id);
        handle.close();
        assertTrue(released.isEmpty());
        writing[0] = false;
        registry.collect();
        assertTrue(released.isEmpty());
        refs.clear();
        registry.collect();
        assertEquals(Collections.singletonList(handle.id), released);
        assertEquals(1, registry.residentBytes());
        registry.released(handle.id, "deferred");
        assertEquals(1, registry.residentBytes());
        registry.released(handle.id, "released");
        assertEquals(0, registry.residentBytes());
    }

    @Test
    public void legacyHostNeverReceivesReleaseAndReplayIsOrdered() {
        List<Integer> registered = new ArrayList<>();
        ResourceRegistry registry =
                new ResourceRegistry(
                        (h, p) -> registered.add(h.id),
                        id -> {
                            fail("legacy release");
                            return false;
                        },
                        () -> false,
                        () -> false,
                        Collections::emptySet,
                        () -> {});
        ResourceHandle a = registry.registerGrayImage(1, 1, ByteBuffer.wrap(new byte[] {9})),
                b = registry.registerGrayImage(1, 1, ByteBuffer.wrap(new byte[] {1}));
        a.close();
        registered.clear();
        registry.replay();
        assertEquals(Arrays.asList(a.id, b.id), registered);
    }

    @Test
    public void workingSetIsValidatedDeduplicatedAndMarkedForReplacement() {
        List<Integer> prefetched = new ArrayList<>();
        boolean[] replace = {false};
        ResourceRegistry registry =
                new ResourceRegistry(
                        (h, p) -> {},
                        id -> true,
                        () -> true,
                        (ids, value) -> {
                            prefetched.addAll(ids);
                            replace[0] = value;
                            return true;
                        },
                        () -> true,
                        () -> false,
                        Collections::emptySet,
                        () -> {});
        ResourceHandle a = registry.registerGrayImage(1, 1, ByteBuffer.wrap(new byte[] {9})),
                b = registry.registerGlyph("mono", 65, 1, 1, ByteBuffer.wrap(new byte[] {1}));
        assertEquals("GLYPH/65/mono", b.wireType);
        ResourceHandle samePixelsDifferentCharacter =
                registry.registerGlyph("mono", 66, 1, 1, ByteBuffer.wrap(new byte[] {1}));
        assertNotEquals(b.id, samePixelsDifferentCharacter.id);
        assertTrue(registry.prefetchWorkingSet(Arrays.asList(a, b, a)));
        assertEquals(Arrays.asList(a.id, b.id), prefetched);
        assertTrue(replace[0]);
        a.close();
        try {
            registry.prefetchImages(a);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void unsupportedHostReceivesNoPrefetch() {
        ResourceRegistry registry =
                new ResourceRegistry(
                        (h, p) -> {},
                        id -> true,
                        () -> true,
                        (ids, replace) -> {
                            fail();
                            return false;
                        },
                        () -> false,
                        () -> false,
                        Collections::emptySet,
                        () -> {});
        ResourceHandle image = registry.registerGrayImage(1, 1, ByteBuffer.wrap(new byte[] {1}));
        assertFalse(registry.prefetch(image));
    }
}
