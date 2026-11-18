import type { WorkSummary } from '../api';
import { WorkItem } from '../ui/WorkItem';

export function WorkList({ works, headingLevel }: { works: WorkSummary[]; headingLevel?: 2 | 3 }) {
  return (
    <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
      {works.map((w) => (
        <li key={w.id}>
          <WorkItem work={w} headingLevel={headingLevel} />
        </li>
      ))}
    </ul>
  );
}
