import { Fragment } from 'react';
import { Alert, Loader } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getWork, type NameDetail, type Snippet, type TextBlock, type TextVersion, type WorkSummary } from '../api';
import { NameTree } from '../components/NameTree';
import { HighlightedText } from '../components/HighlightedText';
import { IdentifierLinks } from '../components/IdentifierLinks';
import { TranslateButton } from '../components/TranslateButton';
import { isEnglish, languageName } from '../languages';
import { Section } from '../components/Section';
import { MetaLine } from '../ui/MetaLine';
import { NotFound, isNotFound } from '../ui/NotFound';
import { paragraphs } from '../ui/paragraphs';
import { SciName } from '../ui/SciName';
import { SciTitle } from '../ui/SciTitle';
import { SideColumn } from '../ui/SideColumn';
import type { NameInfo } from '../ui/nameStyle';
import list from '../ui/list.module.css';
import classes from '../ui/work.module.css';
import { usePageTitle } from '../usePageTitle';

/** Block types come in upper case from the API (HEADING, TITLE, PARAGRAPH …). */
const isHeading = (type: string) => /^(heading|title)/i.test(type);
const isTableCell = (type: string) => /^table_cell$/i.test(type);

/** The blocks in order, each run of consecutive table cells gathered into one array. */
function gatherCells(blocks: TextBlock[]): (TextBlock | TextBlock[])[] {
  const out: (TextBlock | TextBlock[])[] = [];
  for (const b of blocks) {
    const last = out[out.length - 1];
    if (!isTableCell(b.type)) out.push(b);
    else if (Array.isArray(last)) last.push(b);
    else out.push([b]);
  }
  return out;
}

/** A snippet as a block, so its name is marked and linked like a name in the full text. */
const snippetBlock = (s: Snippet): TextBlock =>
  ({ id: `s${s.nameId}`, type: s.blockType, level: 0, sectionPath: [], text: s.text, mentions: s.mentions, acts: s.acts });

/** The label of a stored version of a title, never presenting a machine translation as the article's own. */
function versionLabel(v: TextVersion): string {
  if (v.kind === 'MACHINE') return 'Machine translation';
  return isEnglish(v.lang) ? 'English title' : `Parallel title (${languageName(v.lang)})`;
}

/** A name without a placement: no taxon to show. */
function NameRow({ n }: { n: NameDetail }) {
  return (
    <li>
      <SciName name={n.name} rank={n.rank} usageRank={n.usageRank} kind={n.kind} to={`/name/${n.id}`} acts={n.actTypes} />
      {n.mainSubject ? <span className={classes.note}> main subject</span> : null}
    </li>
  );
}

export function WorkPage() {
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: ['work', id], queryFn: () => getWork(id) });
  const w = q.data;
  usePageTitle(w?.title || `Work ${id}`);
  if (q.isError) {
    return isNotFound(q.error)
      ? <NotFound title="Paper not found">There is no paper at this address.</NotFound>
      : <Alert color="red">{(q.error as Error).message}</Alert>;
  }
  if (!w) return <Loader />;

  const info = new Map<number, NameInfo>(w.names.map((n) => [n.id, { rank: n.rank, usageRank: n.usageRank, kind: n.kind }]));
  // the tree needs a taxon or a classification; a name without either is listed as not placed
  const inTree = (n: NameDetail) => n.placementMethod !== 'NONE' && !!(n.usageId || n.usageName || n.classification.some((c) => c.name));
  const placed = w.names.filter(inTree);
  const none = w.names.filter((n) => !inTree(n));
  const showText = w.retained && w.text && w.text.length > 0;
  // bits of the body text only: title and abstract are on the page already, with their names marked
  const snippets = w.snippets ?? [];
  const [first, ...rest] = paragraphs(w.abstract ?? '', w.abstractMentions ?? [], 'ABSTRACT', w.abstractActs ?? []);
  const authors = w.authors.map((a) => a.name);
  const titleLang = w.titleLang || w.language || undefined;
  const abstractLang = w.abstractLang || w.language || undefined;
  // the other versions of the title: not the original itself
  const otherTitles = (w.titles ?? []).filter((t) => t.kind !== 'ORIGINAL' && t.text !== w.title);
  const hasEnglishTitle = isEnglish(titleLang) || otherTitles.some((t) => isEnglish(t.lang));
  const abstractTranslatable = !!(w.abstract && abstractLang && !isEnglish(abstractLang));
  const otherAbstracts = (w.abstracts ?? []).filter((a) => a.kind !== 'ORIGINAL' && a.text !== w.abstract);

  return (
    <article>
      <h1 className={classes.title} lang={titleLang}>
        {w.title ? <SciTitle title={w.title} mentions={w.titleMentions ?? []} names={info} /> : '(untitled)'}
      </h1>
      {otherTitles.map((t) => (
        <p key={`${t.kind}:${t.lang}`} className={classes.otherTitle}>
          <span className={classes.versionLabel}>
            {versionLabel(t)}
            {t.kind === 'MACHINE' && t.model ? ` (${t.model})` : ''}
            {': '}
          </span>
          <span lang={t.lang || undefined}>{t.text}</span>
        </p>
      ))}
      {w.title && !isEnglish(titleLang) && !hasEnglishTitle ? <TranslateButton text={w.title} lang={titleLang!} showHint={!abstractTranslatable} /> : null}
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
      <IdentifierLinks identifiers={w.identifiers} />

      <div className={`${list.page} ${classes.body}`}>
        <div className={`${list.mainCol} ${classes.text}`}>
          {first ? <p className={classes.lede} lang={abstractLang}><HighlightedText block={first} as="span" names={info} /></p> : null}
          {rest.map((b) => <p key={b.id} className={classes.para} lang={abstractLang}><HighlightedText block={b} as="span" names={info} /></p>)}
          {first && abstractTranslatable ? <TranslateButton text={w.abstract!} lang={abstractLang!} /> : null}
          {otherAbstracts.map((a) => (
            <details key={`${a.kind}:${a.lang}`} className={classes.version}>
              <summary>
                {a.kind === 'MACHINE' ? `Machine translation (${languageName(a.lang)})` : `Abstract in ${languageName(a.lang)}`}
              </summary>
              {paragraphs(a.text, [], 'ABSTRACT', []).map((b) => <p key={b.id} lang={a.lang || undefined}>{b.text}</p>)}
            </details>
          ))}
          {showText ? (
            <Section title="Full text">
              {gatherCells(w.text!).map((b) => (
                Array.isArray(b)
                  // a table's cells flow on in one block at the meta size instead of one paragraph per cell
                  ? (
                    <p key={b[0].id} className={classes.cells}>
                      {b.map((c, i) => (
                        <Fragment key={c.id}>
                          {i > 0 ? ' ' : null}
                          <span className={classes.cell}><HighlightedText block={c} as="span" names={info} /></span>
                        </Fragment>
                      ))}
                    </p>
                  )
                  : isHeading(b.type)
                    ? <h3 key={b.id} className={classes.head}><HighlightedText block={b} as="span" names={info} /></h3>
                    : <div key={b.id} className={classes.para}><HighlightedText block={b} names={info} /></div>
              ))}
            </Section>
          ) : snippets.length ? (
            <Section title="Snippets">
              <ul className={classes.names}>
                {snippets.map((s) => (
                  <li key={s.nameId}><HighlightedText block={snippetBlock(s)} as="span" names={info} /></li>
                ))}
              </ul>
            </Section>
          ) : null}
        </div>
        <SideColumn>
          {placed.length ? <Section title="Names in COL"><NameTree names={placed} /></Section> : null}
          {none.length ? (
            <Section title="Not placed">
              <ul className={classes.names}>{none.map((n) => <NameRow key={n.id} n={n} />)}</ul>
            </Section>
          ) : null}
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
