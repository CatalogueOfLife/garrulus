import { Alert, Loader, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { ApiError, getSource, getTaxGroups } from '../api';
import { FeedLink } from '../components/FeedLink';
import { FollowButton } from '../components/FollowButton';
import { Section } from '../components/Section';
import { TaxGroupIcon } from '../components/TaxGroupIcon';
import { YearBars } from '../components/YearBars';
import { WorkList } from '../components/WorkList';
import { DetailHead } from '../ui/DetailHead';
import { SideColumn } from '../ui/SideColumn';
import { StatRows, type StatRow } from '../ui/StatRows';
import list from '../ui/list.module.css';
import classes from '../ui/detail.module.css';
import { usePageTitle } from '../usePageTitle';
import { subfieldLabel } from '../subfields';

const PUBLIC_TIERS = ['CORE', 'BROAD'];
const pct = (share: number) => `${Math.round(share * 100)}%`;

export function SourcePage() {
  const { id = '' } = useParams();
  const source = useQuery({ queryKey: ['source', id], queryFn: () => getSource(id) });
  const groups = useQuery({ queryKey: ['taxgroups'], queryFn: getTaxGroups, staleTime: Infinity });
  const s = source.data;
  usePageTitle(s?.title ?? `Journal ${id}`);

  if (source.isError) {
    const notFound = source.error instanceof ApiError && source.error.status === 404;
    return <Alert color={notFound ? 'gray' : 'red'}>{notFound ? 'This journal was not found.' : (source.error as Error).message}</Alert>;
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
  if (s.codes.length) cat.push({ label: 'Nomenclatural codes', value: s.codes.join(', ') });
  if (s.journalType) cat.push({ label: 'Journal type', value: s.journalType });
  if (s.oaModel) cat.push({ label: 'Open access', value: s.oaModel });
  if (s.country) cat.push({ label: 'Country', value: s.country });
  if (s.worksPerYear != null) cat.push({ label: 'Papers per year', value: Math.round(s.worksPerYear) });
  if (s.nameDensity != null) cat.push({ label: 'Names per paper', value: s.nameDensity });
  if (s.actRate != null) cat.push({ label: 'Papers with acts', value: pct(s.actRate) });

  return (
    <article>
      <DetailHead
        title={s.title}
        parts={[s.tier, s.publisher, s.issnL && `ISSN-L ${s.issnL}`]}
      />

      <div className={`${list.page} ${classes.body}`}>
        <div className={list.mainCol}>
          <Section title="Recent papers">
            {s.recentWorks.length ? <WorkList works={s.recentWorks} /> : <Text c="dimmed">No papers yet.</Text>}
            <p className={classes.more}>
              <Link to={`/?source=${encodeURIComponent(id)}`}>All papers of this journal</Link>
            </p>
          </Section>
        </div>

        <SideColumn>
          {served ? (
            <div className={classes.actions}>
              <FollowButton type="SOURCE" target={String(s.id)} label={s.title} />
              <FeedLink href={`/feeds/source/${s.id}.atom`} />
            </div>
          ) : null}

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
              <ul className={classes.plain} aria-label="Subfields">
                {s.subfields.map((f) => (
                  <li key={f} title={f} className={f === s.primarySubfield ? classes.primary : undefined}
                    data-primary={f === s.primarySubfield ? 'true' : undefined}>
                    {subfieldLabel(f)}
                  </li>
                ))}
              </ul>
            ) : null}
          </Section>

          <Section title="Papers per year">
            <YearBars years={s.years} />
          </Section>
        </SideColumn>
      </div>
    </article>
  );
}
