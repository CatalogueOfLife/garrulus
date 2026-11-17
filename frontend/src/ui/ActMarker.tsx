import { actLabel } from '../hitNames';

export function ActMarker({ type }: { type: string }) {
  return <span className="gr-act">{actLabel(type)}</span>;
}
