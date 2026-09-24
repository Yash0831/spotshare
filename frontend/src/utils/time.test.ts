import { describe, expect, it } from 'vitest';

import { formatLongRemaining } from './time';

describe('formatLongRemaining', () => {
  it('formats hours and minutes', () => {
    expect(formatLongRemaining(102 * 60 * 1000)).toBe('1 hr 42 min');
  });

  it('formats minutes only under an hour', () => {
    expect(formatLongRemaining(42 * 60 * 1000)).toBe('42 min');
  });

  it('clamps at zero', () => {
    expect(formatLongRemaining(0)).toBe('0 min');
    expect(formatLongRemaining(-5000)).toBe('0 min');
  });
});
