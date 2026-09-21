export type AndroidNotificationAction = {
  index: number;
  title: string;
  enabled: boolean;
  /** Android RemoteInput capability; absent for platforms without inline replies. */
  acceptsText?: boolean;
};

export type AndroidNotification = {
  key: string;
  packageName: string;
  appName: string;
  title: string;
  text: string;
  bigText: string;
  subText: string;
  infoText: string;
  summaryText: string;
  category: string;
  groupKey?: string;
  isGroupSummary?: boolean;
  isForegroundService?: boolean;
  isOngoing?: boolean;
  userId?: number;
  conversationId?: string;
  messages?: { text: string; sender: string; timestamp: number; attachment: boolean }[];
  lines: string[];
  postTime: number;
  when: number;
  actions: AndroidNotificationAction[];
  dismissLabel?: string;
  /** Full message size reported by iOS, even when the fetched text is bounded. */
  messageSize?: number;
};
