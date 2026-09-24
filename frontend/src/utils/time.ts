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
