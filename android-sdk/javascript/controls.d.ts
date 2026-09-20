export type ControlResult = { state: 'applied' | 'rejected' | 'cancelled' | 'unknown' | 'expired'; reason: string; requestId?: string };
export type WindowPolicy = { height: 'min' | 'medium' | 'max'; menuAvailable?: boolean; compact?: boolean; hostBack?: boolean; longPress?: boolean; directional?: boolean };
export type CaptureEvent = { type: 'capture-status' | 'capture-transcript'; status?: string; text?: string; isFinal?: boolean; reason?: string; captureId: string };
export type ComposerEvent = { status: 'confirmed' | 'accepted' | 'cancelled' | 'rejected' | 'expired' | 'unknown'; text?: string; reason?: string; composerId: string };
export function appControls(service: any, bridge?: any): {
 supports(feature: string): boolean;
 onReady(callback: () => void): void;
 request(operation: string, payload?: Record<string, unknown>, timeoutMs?: number): Promise<ControlResult>;
 policy(value: WindowPolicy): Promise<ControlResult>;
 capture(purpose: 'generic' | 'message' | 'search', label: string, listener: (event: CaptureEvent) => void, providerGeneration?: number): {id: string; finish(): void; cancel(): void};
 composer(purpose: 'generic' | 'message', target: string, label: string, initialText: string, maxText: number, listener: (event: ComposerEvent) => void): {id: string; cancel(): void};
 dispose(): void;
};
