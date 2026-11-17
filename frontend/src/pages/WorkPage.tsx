import { Alert, Loader } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getWork, type NameDetail, type WorkSummary } from '../api';
import { ClassificationPath } from '../components/ClassificationPath';
import { HighlightedText } from '../components/HighlightedText';
import { Section } from '../components/Section';
import { MetaLine } from '../ui/MetaLine';
import { SciName } from '../ui/SciName';
import { SciTitle } from '../ui/SciTitle';
import { SideColumn } from '../ui/SideColumn';
import type { NameInfo } from '../ui/nameStyle';
import list from '../ui/list.module.css';
import classes from '../ui/work.module.css';
import { usePageTitle } from '../usePageTitle';

function NameRow({ n, withTaxon }: { n: NameDetail; withTaxon: boolean }) {
  return (
    <li>
      <SciName name={n.name} rank={n.rank} usageRank={n.usageRank} kind={n.kind} to={`/name/${n.id}`} acts={n.actTypes} />
      {withTaxon && n.usageId ? (
        <>
          {' → '}
          <SciName name={n.usageName || n.usageId} rank={n.usageRank} to={`/taxon/${n.usageId}`} />
          {n.usageRank ? <span className={classes.note}> ({n.usageRank})</span> : null}
        </>
      ) : null}
      {n.mainSubject ? <span className={classes.note}> main subject</span> : null}
      {n.classification.length ? <ClassificationPath items={n.classification} /> : null}
    </li>
  );
}

export function WorkPage() {
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: ['work', id], queryFn: () => getWork(id) });
  const w = q.data;
  usePageTitle(w?.title || `Work ${id}`);
  if (q.isError) return <Alert color="red">{(q.error as Error).message}</Alert>;
  if (!w) return <Loader />;

  const info = new Map<number, NameInfo>(w.names.map((n) => [n.id, { rank: n.rank, usageRank: n.usageRank, kind: n.kind }]));
  const placed = w.names.filter((n) => n.placementMethod === 'MATCH' || n.placementMethod === 'GENUS');
  const verb = w.names.filter((n) => n.placementMethod === 'VERBATIM_CONTEXT');
  const none = w.names.filter((n) => n.placementMethod === 'NONE');
  const names = (ns: NameDetail[], withTaxon: boolean) => (
    <ul className={classes.names}>
      {ns.map((n) => <NameRow key={n.id} n={n} withTaxon={withTaxon} />)}
    </ul>
  );
  const showText = w.retained && w.text && w.text.length > 0;
  const snippets = w.names.filter((n) => n.bestSnippet);
  const [first, ...rest] = (w.abstract ?? '').split(/\n{2,}/).map((p) => p.trim()).filter(Boolean);
  const authors = w.authors.map((a) => a.name);

  return (
    <article>
      <h1 className={classes.title}>
        {w.title ? <SciTitle title={w.title} mentions={w.titleMentions ?? []} names={info} /> : '(untitled)'}
      </h1>
      <MetaLine
        authors={authors} authorCount={authors.length} source={w.source} volume={w.volume} issue={w.issue}
        pages={w.pages} date={w.publicationDate} year={w.year} textLevel={w.textLevel as WorkSummary['textLevel']}
      />
      {w.doi || w.licence ? (
        <p className={`${classes.links} gr-wrap`}>
          {w.doi ? <a href={`https://doi.org/${w.doi}`}>doi:{w.doi}</a> : null}
          {w.doi && w.licence ? ' ' : null}
          {w.licence ?? null}
        </p>
      ) : null}

      <div className={`${list.page} ${classes.body}`}>
        <div className={`${list.mainCol} ${classes.text}`}>
          {first ? <p className={classes.lede}>{first}</p> : null}
          {rest.map((p, i) => <p key={i} className={classes.para}>{p}</p>)}
          {showText ? (
            <Section title="Full text">
              {w.text!.map((b) => (
                b.type.startsWith('heading') || b.type === 'title'
                  ? <h3 key={b.id} className={classes.head}><HighlightedText block={b} as="span" names={info} /></h3>
                  : <div key={b.id} className={classes.para}><HighlightedText block={b} names={info} /></div>
              ))}
            </Section>
          ) : snippets.length ? (
            <Section title="Snippets">
              <ul className={classes.names}>
                {snippets.map((n) => <li key={n.id}>{n.bestSnippet}</li>)}
              </ul>
            </Section>
          ) : null}
        </div>
        <SideColumn>
          {placed.length ? <Section title="Placed in COL">{names(placed, true)}</Section> : null}
          {verb.length ? <Section title="Placed by the verbatim classification">{names(verb, true)}</Section> : null}
          {none.length ? <Section title="Not placed">{names(none, false)}</Section> : null}
          {w.contexts.length ? (
            <Section title="Verbatim classification in the article">
              <ul className={classes.names}>
                {w.contexts.map((c) => <li key={c.localId}>{c.verbatim}</li>)}
              </ul>
            </Section>
          ) : null}
        </SideColumn>
      </div>
    </article>
  );
}
