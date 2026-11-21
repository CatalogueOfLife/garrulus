import { Alert, Loader, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { getSource, getTaxGroups, searchWorks } from '../api';
import { CeasedBadge } from '../components/CeasedBadge';
import { IdentifierLinks } from '../components/IdentifierLinks';
import { LanguageBreakdown } from '../components/LanguageBreakdown';
import { FollowActions } from '../components/FollowActions';
import { Section } from '../components/Section';
import { FacetGroup } from '../ui/FacetGroup';
import { TaxGroupIcon } from '../components/TaxGroupIcon';
import { YearBars } from '../components/YearBars';
import { languageItems } from '../components/FilterSidebar';
import { MentionedInFilter, withMentionedIn } from '../components/MentionedInFilter';
import { WorkList } from '../components/WorkList';
import { DetailHead } from '../ui/DetailHead';
import { NotFound, isNotFound } from '../ui/NotFound';
import { SideColumn } from '../ui/SideColumn';
import { StatRows, type StatRow } from '../ui/StatRows';
import { sentence } from '../ui/sentence';
import list from '../ui/list.module.css';
import classes from '../ui/detail.module.css';
import { usePageTitle } from '../usePageTitle';
import { disciplineLabel, useDisciplines } from '../disciplines';

const PUBLIC_TIERS = ['CORE', 'BROAD'];
const pct = (share: number) => `${Math.round(share * 100)}%`;

export function SourcePage() {
  const { id = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const mentionedIn = params.getAll('mentionedIn').filter(Boolean);
  const language = params.getAll('language').filter(Boolean);
  const filtered = mentionedIn.length > 0 || language.length > 0;
  const setList = (key: 'mentionedIn' | 'language', v: string[]) => setParams((p) => {
    const n = new URLSearchParams(p);
    n.delete(key);
    v.forEach((x) => n.append(key, x));
    return n;
  });
  const works = useQuery({
    queryKey: ['source-works', id, mentionedIn, language],
    queryFn: () => searchWorks({ source: id, limit: '10', mentionedIn, language }, ['language']),
  });
  const source = useQuery({ queryKey: ['source', id], queryFn: () => getSource(id) });
  const groups = useQuery({ queryKey: ['taxgroups'], queryFn: getTaxGroups, staleTime: Infinity });
  const disciplines = useDisciplines();
  const s = source.data;
  usePageTitle(s?.title ?? `Journal ${id}`);

  if (source.isError) {
    return isNotFound(source.error)
      ? <NotFound title="Journal not found">There is no journal at this address.</NotFound>
      : <Alert color="red">{(source.error as Error).message}</Alert>;
  }
  if (!s) return <Loader size="sm" />;

  // only the followed journals (CORE, BROAD) have a feed and can be followed; the API lists no others, but be sure
  const served = PUBLIC_TIERS.includes(s.tier);
  const icon = (name: string) => groups.data?.find((g) => g.name === name)?.icon
    ?? `https://api.checklistbank.org/vocab/taxgroup/${encodeURIComponent(name)}/icon.svg`;
  const shares = Object.entries(s.taxgroupShares ?? {}).sort((a, b) => b[1] - a[1]);
  const shown = shares.length ? shares.map(([g, v]) => ({ name: g, share: v as number | null }))
    : s.taxgroups.map((g) => ({ name: g, share: null }));

  const cat: StatRow[] = [];
  if (s.codes.length) cat.push({ label: 'Nomenclatural codes', value: s.codes.map(sentence).join(', ') });
  if (s.journalType) cat.push({ label: 'Journal type', value: sentence(s.journalType) });
  if (s.oaModel) cat.push({ label: 'Open access', value: sentence(s.oaModel) });
  if (s.country) cat.push({ label: 'Country', value: s.country });
  if (s.worksPerYear != null) cat.push({ label: 'Papers per year', value: Math.round(s.worksPerYear) });
  if (s.nameDensity != null) cat.push({ label: 'Names per paper', value: Math.round(s.nameDensity * 10) / 10 });
  if (s.actRate != null) cat.push({ label: 'Papers with acts', value: pct(s.actRate) });

  return (
    <article>
      <DetailHead
        title={s.title}
        parts={[sentence(s.tier), s.publisher, s.issnL && `ISSN-L ${s.issnL}`]}
        actions={served && !language.length
          ? <FollowActions type="SOURCE" target={String(s.id)} label={s.title} feed={withMentionedIn(`/feeds/source/${s.id}.atom`, mentionedIn)} mentionedIn={mentionedIn} />
          : undefined}
      />

      {served && language.length ? <Text size="sm" c="dimmed">Feeds and follows do not filter by language.</Text> : null}

      <IdentifierLinks identifiers={s.identifiers} />

      <CeasedBadge status={s.publicationStatus} lastIssueYear={s.lastIssueYear} continuedBy={s.continuedBy} />

      <div className={`${list.page} ${classes.body}`}>
        <div className={list.mainCol}>
          <Section title="Recent papers">
            <MentionedInFilter value={mentionedIn} onChange={(v) => setList('mentionedIn', v)} />
            {works.data?.facets.language?.length || language.length ? (
              <FacetGroup title="Language" current={language[0]} onSelect={(v) => setList('language', v ? [v] : [])}
                items={languageItems(works.data?.facets.language, language)} />
            ) : null}
            {(() => {
              const list = filtered ? works.data?.results : s.recentWorks;
              if (filtered && works.isError) return <Alert color="red">{(works.error as Error).message}</Alert>;
              if (!list) return <Loader size="sm" />;
              return list.length ? <WorkList works={list} /> : <Text c="dimmed">No papers yet.</Text>;
            })()}
            <p className={classes.more}>
              <Link to={`/?source=${encodeURIComponent(id)}${[...mentionedIn.map((m) => `&mentionedIn=${m}`), ...language.map((l) => `&language=${l}`)].join('')}`}>All papers of this journal</Link>
            </p>
          </Section>
        </div>

        <SideColumn>
          <Section title="Taxonomic groups">
            {shown.length ? (
              <ul className={classes.plain}>
                {shown.map((g) => (
                  <li key={g.name}>
                    <span className={classes.group}>
                      <TaxGroupIcon group={{ name: g.name, icon: icon(g.name) }} />
                      <span>{g.name}</span>
                    </span>
                    {g.share !== null ? <span className={classes.muted}> {pct(g.share)}</span> : null}
                  </li>
                ))}
              </ul>
            ) : <Text c="dimmed">No taxonomic groups recorded.</Text>}
          </Section>

          <Section title="Categorisation">
            <StatRows rows={cat} />
            {s.subfields.length ? (
              <>
                <p id="source-subfields" className={classes.listLabel}>Disciplines</p>
                <ul className={classes.plain} aria-labelledby="source-subfields">
                  {s.subfields.map((f) => (
                    <li key={f} title={f} className={f === s.primarySubfield ? classes.primary : undefined}>
                      {disciplineLabel(f, disciplines.data)}
                      {f === s.primarySubfield ? <span className={classes.muted}> (primary)</span> : null}
                    </li>
                  ))}
                </ul>
                {disciplines.isError ? <Text size="sm" c="dimmed">Discipline names are not available now; codes are shown.</Text> : null}
              </>
            ) : null}
          </Section>

          <Section title="Papers per year">
            <YearBars years={s.years} />
          </Section>

          {s.languages?.length ? (
            <Section title="Languages">
              <LanguageBreakdown languages={s.languages} />
            </Section>
          ) : null}
        </SideColumn>
      </div>
    </article>
  );
}
