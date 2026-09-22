import { AppGlanceWidget } from './app-widget';
import { appGlanceSources } from '../app-content';
import { type GlanceWidgetDefinition, type GlanceWidgetId } from "../widget";
import { CalendarWidget } from "./calendar-widget";
import { CompassWidget } from "./compass-widget";
import { MusicWidget } from "./music-widget";
import { NightscoutWidget } from "./nightscout-widget";
import { SystemCardWidget } from "./system-card";
import { TerminalWidget } from "./terminal-widget";

/** Every widget a board slot can hold. */
export const GLANCE_WIDGETS: readonly GlanceWidgetDefinition[] = [
  { id: "system-card", label: "System card", create: () => new SystemCardWidget() },
  { id: "calendar", label: "Calendar", tall: true, create: () => new CalendarWidget() },
  { id: "terminal", label: "Terminal", tall: true, create: () => new TerminalWidget() },
  { id: "nightscout", label: "Nightscout", create: () => new NightscoutWidget() },
  { id: "compass", label: "Compass", create: () => new CompassWidget() },
  { id: "music", label: "Music", create: () => new MusicWidget() },
];

export function findGlanceWidget(id: GlanceWidgetId | string): GlanceWidgetDefinition | null {
  if (id.startsWith('app:')) {
    const key = id.slice(4), source = appGlanceSources().find(source => source.key === key);
    return { id: id as GlanceWidgetId, label: source?.label || 'Unavailable widget', tall: source?.rows === 2, create: () => new AppGlanceWidget(key) };
  }
  return GLANCE_WIDGETS.find((widget) => widget.id === id) ?? null;
}

/** Whether a slot choice may span two vertically adjacent slots (see GlanceWidgetDefinition.tall). */
export function glanceWidgetSpans(choice: string): boolean {
  return Boolean(findGlanceWidget(choice)?.tall);
}
