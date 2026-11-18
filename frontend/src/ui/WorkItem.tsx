import { Link } from 'react-router-dom';
import type { WorkSummary } from '../api';
import { MetaLine } from './MetaLine';
import { Rule } from './Rule';
import { SciName } from './SciName';
import { SciTitle } from './SciTitle';
import type { NameInfo } from './nameStyle';
import classes from './list.module.css';

/**
 * One work in a list: ink rule above, title with its names in italics, meta line, main names with act markers. The
 * title is an h3 under a section's h2, or an h2 where the list sits right under the page title.
 */
export function WorkItem({ work, headingLevel = 3 }: { work: WorkSummary; headingLevel?: 2 | 3 }) {
  const Title = headingLevel === 2 ? 'h2' : 'h3';
  const info = new Map<number, NameInfo>(work.mainNames.map((n) => [n.id, { rank: n.rank, usageRank: n.usageRank, kind: n.kind }]));
  const anyActs = work.mainNames.some((n) => (n.actTypes ?? []).length > 0);
  return (
    <article>
      <Rule />
      <div className={classes.item}>
        <Title className={classes.title}>
          <Link to={`/work/${work.id}`}>
            {work.title ? <SciTitle title={work.title} mentions={work.titleMentions ?? []} names={info} /> : '(untitled)'}
          </Link>
        </Title>
        <MetaLine
          authors={work.authors}
          authorCount={work.authorCount}
          source={work.source}
          volume={work.volume}
          issue={work.issue}
          pages={work.pages}
          date={work.publicationDate}
          year={work.year}
          textLevel={work.textLevel}
        />
        {work.mainNames.length > 0 || work.hasActs ? (
          <ul className={classes.names}>
            {work.mainNames.map((n) => (
              <li key={n.id}>
                <SciName name={n.name} rank={n.rank} usageRank={n.usageRank} kind={n.kind} to={`/name/${n.id}`} acts={n.actTypes} />
              </li>
            ))}
            {work.hasActs && !anyActs ? <li className={`gr-act ${classes.newNames}`}>new names</li> : null}
          </ul>
        ) : null}
      </div>
    </article>
  );
}
