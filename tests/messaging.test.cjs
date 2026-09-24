const test = require('node:test');
const assert = require('node:assert/strict');
const { Messaging } = require('../.test-build/app/assistant/messaging.js');
const scope = { owner: 'com.example/Service', project: 'project', identity: 'signer-pairing', session: 'session' };
const recipient = {
  id: 'sms:+15550001111',
  title: 'Alice',
  address: '+15550001111',
  accountId: 'sms:1',
  channel: 'sms',
};
const args = { channel: 'sms', recipientId: recipient.id, accountId: recipient.accountId };
const flush = async () => {
  for (let i = 0; i < 10; i++) await Promise.resolve();
};
function fixture(saved = []) {
  let now = 10000,
    count = 0,
    allowed = true,
    failWrite = false,
    sendResult = 'sent',
    resolveRecipient = { ...recipient },
    pendingHistory;
  const reviews = [],
    sends = [],
    writes = [],
    notices = [];
  const provider = {
    status: async () => ({ available: true }),
    search: async () => [resolveRecipient],
    resolve: async () => ({ ...resolveRecipient }),
    history: async () => (pendingHistory ? pendingHistory : { messages: [{ text: 'Private history' }] }),
    send: async (draft, current) => {
      assert.equal(current(), true);
      assert.equal(writes.at(-1).find((d) => d.id === draft.id).status, 'submitting');
      sends.push(draft);
      return { status: sendResult };
    },
    operation: async () => ({ status: sendResult }),
  };
  const store = {
    read: () => structuredClone(saved),
    write: (drafts) => {
      if (failWrite) throw Error('disk');
      writes.push(structuredClone(drafts));
    },
  };
  const broker = new Messaging(
    () => ({ sms: provider }),
    store,
    (review) => {
      reviews.push(review);
      return () => review.cancel();
    },
    () => allowed,
    () => `id-${++count}`,
    () => now,
    (status) => notices.push(status),
  );
  broker.session(scope, true);
  return {
    broker,
    reviews,
    sends,
    writes,
    notices,
    provider,
    deny: () => (allowed = false),
    advance: (n) => (now += n),
    fail: () => (failWrite = true),
    outcome: (v) => (sendResult = v),
    recipient: (v) => (resolveRecipient = v),
    history: (v) => (pendingHistory = v),
    call: (name, p = {}) => broker.call(scope, 'messaging.' + name, p),
    draft: () => broker.call(scope, 'messaging.draft', { ...args, text: 'Hello Alice' }),
  };
}
test('history needs physical consent scoped to project/session/account/conversation', async () => {
  const f = fixture();
  await assert.rejects(f.call('history', args), /permission/);
  assert.equal((await f.call('request_history', args)).status, 'pending');
  await assert.rejects(f.call('history', args), /permission/);
  f.reviews[0].accept();
  assert.match(JSON.stringify(await f.call('history', args)), /Private history/);
  f.broker.session({ ...scope, session: 'another' }, true);
  await assert.rejects(f.call('history', args), /session unavailable/);
  await assert.rejects(f.broker.call({ ...scope, session: 'another' }, 'messaging.history', args), /permission/);
});
test('revocation while history is loading prevents disclosure and cancels pending grants', async () => {
  const f = fixture();
  await f.call('request_history', args);
  f.reviews[0].accept();
  let finish;
  f.history(new Promise((resolve) => (finish = resolve)));
  const read = f.call('history', args);
  await flush();
  await f.call('revoke_history');
  finish({ messages: ['secret'] });
  await assert.rejects(read, /revoked/);
  await f.call('request_history', args);
  await f.call('revoke_history');
  f.reviews[1].accept();
  await assert.rejects(f.call('history', args), /permission/);
});
test('draft and review tools cannot send; only one exact-text confirmation dispatches', async () => {
  const f = fixture(),
    draft = await f.draft();
  assert.equal(f.sends.length, 0);
  await assert.rejects(f.call('send', { draftId: draft.draftId }), /Invalid/);
  await assert.rejects(f.call('review', { draftId: draft.draftId, approved: true }), /Invalid/);
  assert.equal((await f.call('review', { draftId: draft.draftId })).status, 'pending');
  assert.equal(f.sends.length, 0);
  assert.match(f.reviews[0].text, /Alice\n\+15550001111/);
  assert.match(f.reviews[0].text, /Hello Alice/);
  f.reviews[0].accept();
  f.reviews[0].accept();
  await flush();
  assert.equal(f.sends.length, 1);
  assert.equal(f.sends[0].text, 'Hello Alice');
  assert.equal((await f.call('operation', { draftId: draft.draftId })).status, 'sent');
});
test('shared composer refinement replaces the saved draft before the one authorized send', async () => {
  const f = fixture(),
    draft = await f.draft();
  await f.call('review', { draftId: draft.draftId });
  const review = f.reviews[0];
  assert.equal(review.composerText, 'Hello Alice');
  assert.equal(review.destination, 'sms: Alice');
  review.acceptText('Hello Alice, I will be there at six.');
  review.acceptText('Replay');
  await flush();
  assert.equal(f.sends.length, 1);
  assert.equal(f.sends[0].text, 'Hello Alice, I will be there at six.');
  assert.equal((await f.call('get_draft', { draftId: draft.draftId })).text, 'Hello Alice, I will be there at six.');
});
test('bridge loss, expiry, and host revocation invalidate physical review', async () => {
  for (const invalidate of [(f) => f.broker.invalidate(scope.owner), (f) => f.advance(16000), (f) => f.deny()]) {
    const f = fixture(),
      d = await f.draft();
    await f.call('review', { draftId: d.draftId });
    invalidate(f);
    f.reviews[0].accept();
    await flush();
    assert.equal(f.sends.length, 0);
  }
});
test('changed recipient or storage failure cannot dispatch', async () => {
  const f = fixture(),
    d = await f.draft();
  await f.call('review', { draftId: d.draftId });
  f.recipient({ ...recipient, address: '+15550002222' });
  f.reviews[0].accept();
  await flush();
  assert.equal(f.sends.length, 0);
  const g = fixture(),
    e = await g.draft();
  await g.call('review', { draftId: e.draftId });
  g.fail();
  g.reviews[0].accept();
  await flush();
  assert.equal(g.sends.length, 0);
});
test('unknown outcomes survive restart and block new sends without automatic retries', async () => {
  const f = fixture();
  f.outcome('unknown');
  const d = await f.draft();
  await f.call('review', { draftId: d.draftId });
  f.reviews[0].accept();
  await flush();
  assert.equal(f.sends.length, 1);
  const restored = fixture(f.writes.at(-1)),
    next = await restored.draft();
  await assert.rejects(restored.call('review', { draftId: next.draftId }), /unresolved/);
  assert.equal(restored.sends.length, 0);
});
test('process death after submitting restores unknown rather than retrying', async () => {
  const f = fixture(),
    d = await f.draft();
  const saved = f.writes.at(-1);
  saved[0].status = 'submitting';
  saved[0].operationId = 'op';
  const restored = fixture(saved);
  assert.equal(restored.writes.at(-1)[0].status, 'unknown');
  assert.equal(restored.sends.length, 0);
});
test('draft ownership rejects another project and unsupported history recipient', async () => {
  const f = fixture(),
    d = await f.draft();
  const other = { ...scope, project: 'other' };
  f.broker.session(other, true);
  await assert.rejects(f.broker.call(other, 'messaging.review', { draftId: d.draftId }), /unavailable/);
  await assert.rejects(
    f.broker.call(other, 'messaging.draft', { ...args, recipientId: 'unknown', text: 'Hi' }),
    /Recipient changed/,
  );
});
test('hidden control characters are rejected before saving or reviewing', async () => {
  const f = fixture();
  await assert.rejects(f.call('draft', { ...args, text: 'Hello\u202eAlice' }), /control/);
  assert.equal((await f.call('drafts')).length, 0);
});
test('one pending review at a time and cancellation keeps the draft', async () => {
  const f = fixture(),
    a = await f.draft(),
    b = await f.draft();
  await f.call('review', { draftId: a.draftId });
  await assert.rejects(f.call('review', { draftId: b.draftId }), /Another review/);
  await f.call('cancel', { draftId: a.draftId });
  f.reviews[0].accept();
  await flush();
  assert.equal(f.sends.length, 0);
  assert.equal((await f.call('get_draft', { draftId: a.draftId })).text, 'Hello Alice');
});
test('editing invalidates the old review and requires a new exact-text confirmation', async () => {
  const f = fixture(),
    d = await f.draft();
  await f.call('review', { draftId: d.draftId });
  await f.call('update', { draftId: d.draftId, text: 'Corrected message' });
  f.reviews[0].accept();
  await flush();
  assert.equal(f.sends.length, 0);
  await f.call('review', { draftId: d.draftId });
  assert.match(f.reviews[1].text, /Corrected message/);
  f.reviews[1].accept();
  await flush();
  assert.equal(f.sends[0].text, 'Corrected message');
});
test('changing pairing or signer cannot read drafts from the earlier principal', async () => {
  const f = fixture(),
    d = await f.draft(),
    other = { ...scope, identity: 'different-pairing' };
  f.broker.session(other, true);
  assert.deepEqual(await f.broker.call(other, 'messaging.drafts', {}), []);
  await assert.rejects(f.broker.call(other, 'messaging.get_draft', { draftId: d.draftId }), /unavailable/);
});
test('provider expiry bounds the lifetime of a retained draft', async () => {
  const f = fixture();
  f.recipient({ ...recipient, expiresAt: 11000 });
  const d = await f.draft();
  f.advance(1001);
  await assert.rejects(f.call('review', { draftId: d.draftId }), /expired|unavailable/);
  await assert.rejects(f.call('get_draft', { draftId: d.draftId }), /expired|unavailable/);
});
test('SMS submission reconciles callbacks without replaying the send', async () => {
  const f = fixture();
  f.outcome('submitting');
  const d = await f.draft();
  await f.call('review', { draftId: d.draftId });
  f.reviews[0].accept();
  await flush();
  assert.equal(f.sends.length, 1);
  assert.ok(f.notices.includes('Sending message...'));
  f.outcome('sent');
  f.broker.tick();
  await flush();
  assert.equal(f.sends.length, 1);
  assert.ok(f.notices.includes('Message sent'));
});
test('disconnect with cleared configuration still revokes the matching desktop session', async () => {
  const f = fixture(),
    d = await f.draft();
  await f.call('review', { draftId: d.draftId });
  f.broker.session({ ...scope, project: '', identity: '' }, false);
  f.reviews[0].accept();
  await flush();
  assert.equal(f.sends.length, 0);
});
