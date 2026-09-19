declare const com: any;

export interface BleTrafficSample {
  /** Outbound messages (writeFrames calls) since process start. */
  messages: number;
  /** Outbound payload bytes (sum of frame sizes) since process start. */
  bytes: number;
  /** Display frames whose last message was acked, since process start. */
  frames: number;
  /** Physical characteristic-write retries since process start. */
  retries: number;
  /** Android connection-priority requests and requests accepted for processing. */
  priorityRequests: number;
  priorityAccepted: number;
}

/** Running totals of outbound BLE traffic, counted process-wide in FaceclawBleManager. */
export function sampleBleTraffic(): BleTrafficSample {
  const raw = com.faceclaw.app.FaceclawBleManager.sampleOutboundTraffic();
  return {
    messages: Number(raw[0]), bytes: Number(raw[1]), frames: Number(raw[2]),
    retries: Number(raw[3] ?? 0), priorityRequests: Number(raw[4] ?? 0), priorityAccepted: Number(raw[5] ?? 0),
  };
}

/** Content-free, communicator-scoped activity snapshot for battery experiments. */
export function sampleGlassesActivity(): Record<string, unknown> | null {
  const active = com.faceclaw.app.FaceclawBleCommunicator.getActive();
  if (!active) return null;
  return JSON.parse(String(active.getBatteryActivitySnapshotJson()));
}
