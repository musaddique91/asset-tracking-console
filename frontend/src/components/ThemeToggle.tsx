import type { Theme } from '../theme';

export function ThemeToggle({ theme, onToggle }: { theme: Theme; onToggle(): void }) {
  const light = theme === 'light';
  return (
    <button
      type="button"
      className="theme-toggle"
      aria-pressed={light}
      aria-label="Light theme"
      title={light ? 'Switch to dark theme' : 'Switch to light theme'}
      onClick={onToggle}
    >
      <span aria-hidden="true">{light ? '☾' : '☀'}</span>
    </button>
  );
}
