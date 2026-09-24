import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useCountdown } from './useCountdown';

const MIN = 60 * 1000;
const T0 = Date.parse('2026-09-24T12:00:00Z');

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(T0);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useCountdown', () => {
  it('labels the remaining time in the long form', () => {
    const { result } = renderHook(() => useCountdown(new Date(T0 + 102 * MIN).toISOString()));
    expect(result.current.label).toBe('1 hr 42 min');
    expect(result.current.isFinalStretch).toBe(false);
    expect(result.current.isOver).toBe(false);
  });

  it('ticks down once a second', () => {
    const { result } = renderHook(() => useCountdown(new Date(T0 + 61 * MIN).toISOString()));
    expect(result.current.label).toBe('1 hr 1 min');
    act(() => {
      vi.advanceTimersByTime(60 * 1000);
    });
    expect(result.current.label).toBe('1 hr 0 min');
  });

  it('flags the 15-minute threshold for the in-app reminder', () => {
    const { result } = renderHook(() => useCountdown(new Date(T0 + 90 * MIN).toISOString()));
    expect(result.current.isFinalStretch).toBe(false);

    // 15 minutes left, on the dot: still the final stretch.
    act(() => {
      vi.advanceTimersByTime(75 * MIN);
    });
    expect(result.current.label).toBe('15 min');
    expect(result.current.isFinalStretch).toBe(true);

    // 1 second over the threshold: not yet.
    const { result: early } = renderHook(() =>
      useCountdown(new Date(T0 + 15 * MIN + 1000).toISOString()),
    );
    expect(early.current.isFinalStretch).toBe(false);
  });

  it('reports overdue once the end instant passes', () => {
    const { result } = renderHook(() => useCountdown(new Date(T0 + 5 * MIN).toISOString()));
    act(() => {
      vi.advanceTimersByTime(6 * MIN);
    });
    expect(result.current.isOver).toBe(true);
    expect(result.current.label).toBe('0 min');
    expect(result.current.isFinalStretch).toBe(false);
  });
});
