import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useIsMobile } from './useIsMobile';

type Listener = (event: MediaQueryListEvent) => void;

function stubMatchMedia(matches: boolean) {
  const listeners = new Set<Listener>();
  const matchMedia = vi.fn((query: string) => ({
    matches,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: (_type: string, listener: Listener) => { listeners.add(listener); },
    removeEventListener: (_type: string, listener: Listener) => { listeners.delete(listener); },
    dispatchEvent: vi.fn(),
  }));
  vi.stubGlobal('matchMedia', matchMedia);
  return {
    matchMedia,
    emit(next: boolean) {
      listeners.forEach((listener) => listener({ matches: next } as MediaQueryListEvent));
    },
  };
}

describe('useIsMobile', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('reads the mobile breakpoint on mount', () => {
    const media = stubMatchMedia(true);
    const { result } = renderHook(() => useIsMobile());

    expect(result.current).toBe(true);
    expect(media.matchMedia).toHaveBeenCalledWith('(max-width: 767.98px)');
  });

  it('follows media query changes and unsubscribes on unmount', () => {
    const media = stubMatchMedia(false);
    const { result, unmount } = renderHook(() => useIsMobile());
    expect(result.current).toBe(false);

    act(() => media.emit(true));
    expect(result.current).toBe(true);

    unmount();
    act(() => media.emit(false));
    expect(result.current).toBe(true);
  });

  it('falls back to desktop when matchMedia is unavailable', () => {
    vi.stubGlobal('matchMedia', undefined);
    const { result } = renderHook(() => useIsMobile());

    expect(result.current).toBe(false);
  });
});
