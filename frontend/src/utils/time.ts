/** Small time formatting helpers for display only — no business logic. */

const timeFmt = new Intl.DateTimeFormat('en-US', {
  hour: 'numeric',
  minute: '2-digit',
});

const dateTimeFmt = new Intl.DateTimeFormat('en-US', {
  weekday: 'short',
  hour: 'numeric',
  minute: '2-digit',
});

/** "5:30 PM" from an ISO instant, in the viewer's timezone. */
export function formatTime(iso: string): string {
  return timeFmt.format(new Date(iso));
}

/** "Thu 5:30 PM" — for upcoming shares that may be on another day. */
export function formatDateTime(iso: string): string {
  return dateTimeFmt.format(new Date(iso));
}

/**
 * "1h 30m" style remaining-time label for a countdown between now and an
 * end instant.
 */
export function formatRemaining(ms: number): string {
  if (ms <= 0) return '0m';
  const totalMinutes = Math.floor(ms / 60000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) return `${minutes}m`;
  return `${hours}h ${minutes}m`;
}

/**
 * "1 hr 42 min" style remaining-time label — the long form used by the
 * active-parking countdown ("1 hr 42 min remaining, leave by 10:30 PM").
 */
export function formatLongRemaining(ms: number): string {
  if (ms <= 0) return '0 min';
  const totalMinutes = Math.floor(ms / 60000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) return `${minutes} min`;
  return `${hours} hr ${minutes} min`;
}

/**
 * "3 days 15 hours" style trip-length label for the vacation-mode summary.
 * Days and hours only — minutes are noise on a multi-day trip.
 */
export function formatTripLength(ms: number): string {
  if (ms <= 0) return '0 hours';
  const totalMinutes = Math.floor(ms / 60000);
  const days = Math.floor(totalMinutes / 1440);
  const hours = Math.floor((totalMinutes % 1440) / 60);
  const parts: string[] = [];
  if (days > 0) parts.push(`${days} ${days === 1 ? 'day' : 'days'}`);
  if (hours > 0) parts.push(`${hours} ${hours === 1 ? 'hour' : 'hours'}`);
  if (parts.length === 0) return `${totalMinutes} min`;
  return parts.join(' ');
}
