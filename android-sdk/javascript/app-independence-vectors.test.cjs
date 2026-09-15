'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const vectors = JSON.parse(fs.readFileSync(path.join(__dirname, '../test-vectors/app-independence-v1.json'), 'utf8'));

test('shared app-independence vectors have bounded catalog and request cases', () => {
  assert.equal(vectors.version, 1);
  assert.ok(vectors.catalogCases.length >= 4);
  for (const vector of vectors.catalogCases) {
    assert.ok(['ABSENT', 'VALID', 'MALFORMED'].includes(vector.state));
    assert.ok(vector.capabilities && typeof vector.capabilities === 'object');
    assert.ok(Array.isArray(vector.supports));
  }
  for (const request of vectors.requestCases) {
    assert.match(request.requestId, /^[A-Za-z0-9_-]{1,128}$/);
    assert.ok(['idempotent', 'conflict', 'stale_window', 'expired'].includes(request.expected));
  }
});

test('shared UTF-8 exact and over-limit fixtures measure encoded bytes', () => {
  for (const vector of vectors.boundaryCases) assert.equal(Buffer.byteLength(JSON.stringify({text:vector.text}), 'utf8') <= vector.maxBytes, vector.valid);
});
