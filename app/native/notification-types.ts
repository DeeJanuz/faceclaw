export type AndroidNotificationAction = {
  index: number;
  title: string;
  enabled: boolean;
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
  lines: string[];
  postTime: number;
  when: number;
  actions: AndroidNotificationAction[];
};

