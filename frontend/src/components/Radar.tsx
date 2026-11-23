interface Props {
  /** Diameter in pixels. */
  size: number;
  /** When false the sweep stops (for example while live updates are interrupted). */
  active?: boolean;
  /** Show fading contacts (decorative; used on the sign-in screen). */
  blips?: boolean;
  className?: string;
}

// Fixed contact positions (percent of the scope, polar-ish) and their delay in the 4 s sweep.
const BLIPS: { x: number; y: number; delay: number }[] = [
  { x: 68, y: 30, delay: 0.45 },
  { x: 78, y: 62, delay: 1.1 },
  { x: 45, y: 80, delay: 2.0 },
  { x: 22, y: 58, delay: 2.75 },
  { x: 32, y: 24, delay: 3.5 },
  { x: 58, y: 48, delay: 1.6 },
];

/**
 * Decorative radar. Pure CSS: only transform and opacity animate, so the browser runs it on the
 * compositor and it costs no main-thread time. Hidden from assistive technology, and still under
 * prefers-reduced-motion (see styles.css).
 */
export function Radar({ size, active = true, blips = false, className = '' }: Props) {
  return (
    <div
      className={`radar ${active ? 'radar-on' : 'radar-off'} ${className}`}
      style={{ width: size, height: size }}
      aria-hidden="true"
    >
      <div className="radar-rings" />
      <div className="radar-sweep" />
      {blips &&
        BLIPS.map((b, i) => (
          <span key={i} className="radar-blip" style={{ left: `${b.x}%`, top: `${b.y}%`, animationDelay: `${b.delay}s` }} />
        ))}
    </div>
  );
}
