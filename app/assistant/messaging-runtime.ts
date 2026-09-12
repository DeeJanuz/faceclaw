import { Utils } from '@nativescript/core';
import { Messaging, type MessagingProvider, type MessagingScope, type MessageDraft, type Recipient } from './messaging';
import { shell } from '../ui/shell/shell';
import { readActiveNotifications, replyToNotification, type AndroidNotification } from '../native/notification-icons';
declare const com: any, java: any;
const id = () => String(java.util.UUID.randomUUID().toString());
type Pending = { component: string; resolve: (result: any) => void; reject: () => void; current: () => boolean; timer: ReturnType<typeof setTimeout> };

/** Adapter between the pure authority broker, native SMS, and approved SDK providers. */
export class HostMessaging {
  readonly broker: Messaging;
  private pending = new Map<string, Pending>();
  private sessionKey = "";
  private notifications = new Map<string, { source: AndroidNotification; index: number }>();
  constructor(private native: any, available: (owner: string) => boolean) {
    const storage = new com.faceclaw.app.FaceclawMessagingStore(Utils.android.getApplicationContext(), 'drafts');
    this.broker = new Messaging(() => this.providers(), { read: () => JSON.parse(String(storage.read())), write: drafts => storage.write(JSON.stringify(drafts)) },
      review => shell.startMessagingReview(review), available, id, Date.now, status => shell.showAlert(status));
  }
  session(scope: MessagingScope, active: boolean): boolean {
    const next = JSON.stringify(scope);
    if (!active || next !== this.sessionKey) this.cancelNative();
    this.sessionKey = active ? next : "";
    return this.broker.session(scope, active);
  }
  invalidate(): void { this.cancelNative(); this.broker.invalidate(); this.sessionKey = ""; }
  tick(): void {
    this.broker.tick();
    if (this.sessionKey && !this.broker.connected(JSON.parse(this.sessionKey))) this.invalidate();
    for (const [requestId, pending] of this.pending) if (!pending.current()) {
      this.native.send(pending.component, 'messaging-cancel', JSON.stringify({ requestId }));
      clearTimeout(pending.timer); this.pending.delete(requestId); pending.reject();
    }
  }
  private cancelNative(): void {
    com.faceclaw.app.FaceclawSms.get(Utils.android.getApplicationContext()).invalidateReviews();
    for (const [requestId, pending] of this.pending) {
      this.native.send(pending.component, 'messaging-cancel', JSON.stringify({ requestId }));
      clearTimeout(pending.timer); pending.reject();
    }
    this.pending.clear();
  }
  private signalComponent(): string | undefined {
    const apps = JSON.parse(String(this.native.installedJson())).filter((app: any) => app.connected && app.messaging === 'signal');
    return apps.length === 1 ? apps[0].component : undefined;
  }
  private request(channel: string, method: string, params: unknown, current: () => boolean = () => true): Promise<any> {
    if (!current()) return Promise.reject(new Error('Review expired'));
    if (channel === 'sms') return new Promise((resolve, reject) => {
      const callback = new com.faceclaw.app.FaceclawMessagingCallback({ onResult: (json: string) => {
        try { const result = JSON.parse(String(json)); if (result.error) reject(new Error(result.error)); else resolve(result); } catch { reject(new Error('SMS response unavailable')); }
      } });
      com.faceclaw.app.FaceclawSms.get(Utils.android.getApplicationContext()).request(method, JSON.stringify(params), callback);
    });
    const component = this.signalComponent(); if (!component) return Promise.reject(new Error('Approve one Signal messaging provider in Faceclaw settings'));
    const requestId = id();
    return new Promise((resolve, reject) => {
      const fail = () => reject(new Error('Signal result unavailable or outcome unknown'));
      const timer = setTimeout(() => { this.pending.delete(requestId); this.native.send(component, 'messaging-cancel', JSON.stringify({ requestId })); fail(); }, 29000);
      this.pending.set(requestId, { component, resolve, reject: fail, timer, current });
      this.native.send(component, 'messaging-request', JSON.stringify({ requestId, method, params, expiresAt: Date.now() + 30000 }));
    });
  }
  event(component: string, type: string, data: any): boolean {
    if (['disconnected','grants-changed','changed'].includes(type)) {
      // Revoking provider permission also invalidates outstanding history and review authority.
      this.invalidate();
      for (const [requestId, pending] of this.pending) if (!component || component === pending.component) {
        clearTimeout(pending.timer); this.pending.delete(requestId); pending.reject();
      }
    }
    if (type !== 'messaging-result') return false;
    const pending = this.pending.get(data.requestId);
    if (!pending || pending.component !== component || component !== this.signalComponent()) return true;
    clearTimeout(pending.timer); this.pending.delete(data.requestId);
    if (!data.result || data.result.error || JSON.stringify(data.result).length > 24000) pending.reject(); else pending.resolve(data.result);
    return true;
  }
  private provider(channel: string): MessagingProvider {
    const parameters = (r: Recipient) => ({ recipientId: r.id, accountId: r.accountId });
    return {
      status: () => this.request(channel, 'status', {}),
      search: async query => (await this.request(channel, 'search', { query })).recipients,
      resolve: (recipientId, accountId) => this.request(channel, 'resolve', { recipientId, accountId }),
      history: r => this.request(channel, 'history', parameters(r)),
      send: (draft, current) => this.request(channel, 'send', { ...parameters(draft.recipient), text: draft.text, operationId: draft.operationId, expiresAt: Date.now() + 25000 }, current),
      operation: draft => this.request(channel, 'operation', { ...parameters(draft.recipient), operationId: draft.operationId }),
    };
  }
  private notificationRecipient(target: string): Recipient {
    const entry = this.notifications.get(target);
    if (!entry || !readActiveNotifications(50, false).some(n => n.key === entry.source.key && n.postTime === entry.source.postTime && n.actions.some(a => a.index === entry.index && a.enabled && a.acceptsText))) throw new Error('Notification is no longer replyable');
    return { id: target, title: entry.source.title || entry.source.appName, address: entry.source.appName, accountId: 'notifications', channel: 'notification', version: String(entry.source.postTime) };
  }
  private providers(): Record<string, MessagingProvider> {
    return { sms: this.provider('sms'), ...(this.signalComponent() ? { signal: this.provider('signal') } : {}), notification: {
      status: async () => ({ replyOnly: true }), search: async () => [], resolve: async target => this.notificationRecipient(target),
      history: async () => { throw new Error('Use the conversation history provider'); },
      send: async (draft, current) => {
        const recipient = this.notificationRecipient(draft.recipient.id), entry = this.notifications.get(recipient.id)!;
        if (!current()) throw new Error('Review expired');
        // Android handoff is not delivery confirmation. Never replay this PendingIntent.
        const accepted = replyToNotification(entry.source.key, entry.index, entry.source.postTime, draft.text);
        if (accepted) this.notifications.delete(recipient.id);
        return { status: accepted ? 'handed-off' : 'unknown' };
      }, operation: async () => ({ status: 'unknown' }),
    } };
  }
  async reviewNotification(scope: MessagingScope, source: AndroidNotification, index: number, text: string): Promise<unknown> {
    if (!source.actions.some(a => a.index === index && a.enabled && a.acceptsText) || source.key.startsWith('apk:')) throw new Error('Reply unavailable');
    const recipientId = String(com.faceclaw.app.FaceclawMessagingStore.fingerprint(JSON.stringify([source.key,index])));
    if (!this.notifications.has(recipientId) && this.notifications.size >= 64) throw new Error('Notification review limit reached');
    this.notifications.set(recipientId, { source, index });
    const draft = await this.broker.call(scope, 'messaging.draft', { channel: 'notification', accountId: 'notifications', recipientId, text }) as { draftId: string };
    return this.broker.call(scope, 'messaging.review', { draftId: draft.draftId });
  }
}
