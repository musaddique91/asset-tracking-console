import { useCallback, useEffect, useState } from 'react';

export type Theme = 'dark' | 'light';
const KEY = 'zw.theme';

function stored(): Theme | null {
  try {
    const v = localStorage.getItem(KEY);
    return v === 'light' || v === 'dark' ? v : null;
  } catch {
    return null;
  }
}

function system(): Theme {
  return window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

function apply(theme: Theme): void {
  document.documentElement.dataset.theme = theme;
}

/** Applied before React renders (main.tsx), so the first paint already has the right colours. */
export function initTheme(): void {
  apply(stored() ?? system());
}

/**
 * The operator's explicit choice wins and is remembered; without one the console follows the
 * operating system, including when that changes while the console is open.
 */
export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(() => stored() ?? system());

  useEffect(() => {
    apply(theme);
  }, [theme]);

  useEffect(() => {
    const mq = window.matchMedia?.('(prefers-color-scheme: light)');
    if (!mq) return;
    const onChange = () => {
      if (stored()) return;
      apply(system());
      setTheme(system());
    };
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  const toggle = useCallback(() => {
    setTheme((t) => {
      const next: Theme = t === 'dark' ? 'light' : 'dark';
      // set on <html> now, not in an effect: child effects (the map) run first and read the new colours
      apply(next);
      try {
        localStorage.setItem(KEY, next);
      } catch {
        // storage unavailable: the choice lasts for this page only
      }
      return next;
    });
  }, []);

  return [theme, toggle];
}

/** Reads a CSS custom property of the current theme (used by the map). */
export function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}
