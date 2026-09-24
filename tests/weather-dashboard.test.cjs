const test = require('node:test');
const assert = require('node:assert/strict');

const vm = require('node:vm');
const { transpile } = require('./helpers/transpile.cjs');
function harness() {
  let now = 100000,
    granted = true,
    calls = 0,
    fail = false,
    locationTime,
    pending;
  const intervals = new Map();
  const imports = {
    './location-permissions': { hasLocationPermission: () => granted },
    './location': {
      getCurrentLocation: async () => {
        calls++;
        if (pending) await pending;
        if (fail) throw new Error('No location');
        return { latitude: 40, longitude: -105, timestampMs: locationTime };
      },
    },
    '../version': { USER_AGENT: 'test' },
    '../util/http': {
      fetchWithUserAgent: async (url) => ({
        ok: true,
        json: async () =>
          url.includes('/points/')
            ? {
                properties: {
                  forecast: 'https://api.weather.gov/forecast',
                  relativeLocation: { properties: { city: 'Test', state: 'CO' } },
                },
              }
            : {
                properties: {
                  periods: [
                    {
                      name: 'Today',
                      startTime: '2026-09-15T12:00:00Z',
                      temperature: 70,
                      temperatureUnit: 'F',
                      shortForecast: 'Clear',
                      isDaytime: true,
                    },
                  ],
                },
              },
      }),
    },
  };
  const module = { exports: {} };
  vm.runInNewContext(transpile('app/native/weather.ts'), {
    module,
    exports: module.exports,
    require: (n) => imports[n],
    Date: class extends Date {
      static now() {
        return now;
      }
    },
    setTimeout,
    clearTimeout,
    setInterval: (fn) => {
      intervals.set(1, fn);
      return 1;
    },
    clearInterval: (id) => intervals.delete(id),
    console: { warn() {} },
  });
  return {
    bridge: new module.exports.WeatherBridge(),
    calls: () => calls,
    advance: (ms) => (now += ms),
    grant: (v) => (granted = v),
    fail: (v) => (fail = v),
    locationAt: (ms) => (locationTime = ms),
    now: () => now,
    hold: () => {
      let release;
      pending = new Promise((resolve) => (release = resolve));
      return () => {
        release();
        pending = null;
      };
    },
    intervals,
  };
}
const flush = () => new Promise(setImmediate);
test('visible dashboard starts weather without opening Weather and refreshes every 30 minutes', async () => {
  const h = harness();
  h.bridge.snapshotForDashboard(false);
  await flush();
  assert.equal(h.calls(), 0);
  h.bridge.snapshotForDashboard(true);
  await flush();
  assert.equal(h.calls(), 1);
  assert.equal(h.bridge.snapshot().phase, 'ready');
  assert.equal(h.bridge.snapshot().current.temperatureF, 70);
  assert.equal(h.intervals.size, 0);
  for (let i = 0; i < 10; i++) h.bridge.snapshotForDashboard(true);
  await flush();
  assert.equal(h.calls(), 1);
  h.advance(30 * 60 * 1000);
  h.bridge.snapshotForDashboard(false);
  await flush();
  assert.equal(h.calls(), 1);
  h.bridge.snapshotForDashboard(true);
  await flush();
  assert.equal(h.calls(), 2);
});
test('closing Weather does not stop dashboard demand and simultaneous polls share a request', async () => {
  const h = harness();
  h.bridge.start();
  h.bridge.snapshotForDashboard(true);
  h.bridge.snapshotForDashboard(true);
  await flush();
  assert.equal(h.calls(), 1);
  h.bridge.stop();
  h.advance(30 * 60 * 1000);
  h.bridge.snapshotForDashboard(true);
  await flush();
  assert.equal(h.calls(), 2);
});
test('dashboard detects permission grant and revocation without prompting or leaking old conditions', async () => {
  const h = harness();
  h.grant(false);
  h.bridge.snapshotForDashboard(true);
  await flush();
  assert.equal(h.calls(), 0);
  h.grant(true);
  h.bridge.snapshotForDashboard(true);
  await flush();
  assert.equal(h.bridge.snapshot().phase, 'ready');
  h.grant(false);
  assert.equal(h.bridge.snapshotForDashboard(true).phase, 'permission-required');
  assert.equal(h.bridge.snapshot().current, null);
  h.grant(true);
  h.bridge.snapshotForDashboard(true);
  await flush();
  assert.equal(h.calls(), 2);
});
test('failed lookups retry after one minute instead of every ambient poll', async () => {
  const h = harness();
  h.fail(true);
  h.bridge.snapshotForDashboard(true);
  await flush();
  assert.equal(h.bridge.snapshot().phase, 'error');
  h.advance(59999);
  h.bridge.snapshotForDashboard(true);
  await flush();
  assert.equal(h.calls(), 1);
  h.advance(1);
  h.fail(false);
  h.bridge.snapshotForDashboard(true);
  await flush();
  assert.equal(h.calls(), 2);
  assert.equal(h.bridge.snapshot().phase, 'ready');
});

test('permission revoked during a lookup cannot publish the completed weather', async () => {
  const h = harness(),
    release = h.hold();
  h.bridge.snapshotForDashboard(true);
  await flush();
  assert.equal(h.calls(), 1);
  h.grant(false);
  h.bridge.snapshotForDashboard(true);
  release();
  await flush();
  assert.equal(h.bridge.snapshot().phase, 'permission-required');
  assert.equal(h.bridge.snapshot().current, null);
});
test('reentrant dashboard consumers cannot start overlapping location requests', async () => {
  const h = harness();
  h.bridge.onStateChange(() => h.bridge.snapshotForDashboard(true));
  await flush();
  assert.equal(h.calls(), 1);
  assert.equal(h.bridge.snapshot().phase, 'ready');
});

test('weather from an old saved location says how old it is and how to update it', async () => {
  const h = harness();
  h.advance(10 * 24 * 60 * 60 * 1000);
  h.locationAt(h.now() - 3 * 24 * 60 * 60 * 1000);
  await h.bridge.refreshNow();
  const stale = h.bridge.snapshot();
  assert.equal(stale.phase, 'ready');
  assert.equal(stale.locationName, 'Test, CO · location from 3 days ago');
  assert.match(stale.status, /where your phone was 3 days ago\. Open Faceclaw on your phone/);
  assert.equal(stale.locationTimestampMs, h.now() - 3 * 24 * 60 * 60 * 1000);
  h.locationAt(h.now() - 5 * 60 * 60 * 1000);
  await h.bridge.refreshNow();
  assert.equal(h.bridge.snapshot().locationName, 'Test, CO · location from 5 hours ago');
  h.locationAt(h.now() - 5 * 60 * 1000);
  await h.bridge.refreshNow();
  assert.equal(h.bridge.snapshot().locationName, 'Test, CO');
  assert.equal(h.bridge.snapshot().status, 'Weather updated.');
});

test('opening Faceclaw refreshes an old or failed location but not a fresh one', async () => {
  const h = harness();
  h.locationAt(h.now() - 60 * 1000);
  await h.bridge.refreshNow();
  assert.equal(h.calls(), 1);
  h.bridge.refreshIfLocationStale();
  await flush();
  assert.equal(h.calls(), 1, 'a fix from a minute ago is kept');
  h.advance(11 * 60 * 1000);
  h.bridge.refreshIfLocationStale();
  await flush();
  assert.equal(h.calls(), 2, 'an older fix is refreshed while the screen is open');
  h.fail(true);
  await h.bridge.refreshNow();
  assert.equal(h.bridge.snapshot().phase, 'error');
  h.fail(false);
  h.locationAt(h.now());
  h.bridge.refreshIfLocationStale();
  await flush();
  assert.equal(h.bridge.snapshot().phase, 'ready');
  h.grant(false);
  const before = h.calls();
  h.bridge.refreshIfLocationStale();
  await flush();
  assert.equal(h.calls(), before, 'no permission means no location request');
});

test('a location with no known or implausible timestamp is never labelled old', async () => {
  const h = harness();
  await h.bridge.refreshNow();
  assert.equal(h.bridge.snapshot().locationName, 'Test, CO');
  assert.equal(h.bridge.snapshot().locationTimestampMs, null);
  h.advance(40 * 24 * 60 * 60 * 1000);
  for (const timestampMs of [1234, h.now() + 60 * 60 * 1000]) {
    h.locationAt(timestampMs);
    await h.bridge.refreshNow();
    assert.equal(h.bridge.snapshot().locationName, 'Test, CO');
    assert.equal(h.bridge.snapshot().locationTimestampMs, null);
  }
});
