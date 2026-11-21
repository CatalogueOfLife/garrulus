import { Alert, Anchor, Loader, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { getName, searchWorks } from '../api';
import { FollowActions } from '../components/FollowActions';
import { Section } from '../components/Section';
import { ClassificationPath } from '../components/ClassificationPath';
import { MentionedInFilter, withMentionedIn } from '../components/MentionedInFilter';
import { WorkList } from '../components/WorkList';
import { YearBars } from '../components/YearBars';
import { DetailHead } from '../ui/DetailHead';
import { formatDate } from '../ui/MetaLine';
import { NotFound, isNotFound } from '../ui/NotFound';
import { SciName } from '../ui/SciName';
import { SideColumn } from '../ui/SideColumn';
import { words } from '../ui/sentence';
import list from '../ui/list.module.css';
import classes from '../ui/detail.module.css';
import { usePageTitle } from '../usePageTitle';

export function NamePage() {
  const { id = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const mentionedIn = params.getAll('mentionedIn').filter(Boolean);
  const setMentioned = (v: string[]) => setParams((p) => { const n = new URLSearchParams(p); n.delete('mentionedIn'); v.forEach((x) => n.append('mentionedIn', x)); return n; });
  const name = useQuery({ queryKey: ['name', id], queryFn: () => getName(id) });
  const works = useQuery({ queryKey: ['name-works', id, mentionedIn], queryFn: () => searchWorks({ name: id, limit: '20', mentionedIn }) });
  const n = name.data;
  usePageTitle(n?.name ?? `Name ${id}`);

  if (name.isError) {
    return isNotFound(name.error)
      ? <NotFound title="Name not found">There is no name at this address.</NotFound>
      : <Alert color="red">{(name.error as Error).message}</Alert>;
  }
  if (!n) return <Loader size="sm" />;
  const m = n.match;
  const synonym = m?.acceptedId && m.acceptedId !== m.usageId;
  const first = n.firstAppearance;

  return (
    <article>
      <DetailHead
        title={<SciName name={n.name} rank={n.rank} kind={n.kind} />}
        parts={[words(n.rank), n.kind === 'OTU' ? n.kind : words(n.kind)]}
        actions={<FollowActions type="NAME" target={String(n.id)} label={n.name} feed={withMentionedIn(`/feeds/name/${n.id}.atom`, mentionedIn)} mentionedIn={mentionedIn} />}
      />

      <div className={`${list.page} ${classes.body}`}>
        <div className={list.mainCol}>
          <Section title="Match in COL">
            {m?.usageId ? (
              <div>
                <Text>
                  <SciName name={m.usageName || m.usageId} rank={m.rank} to={`/taxon/${m.usageId}`} />
                  {m.rank ? <span className={classes.muted}> ({words(m.rank)})</span> : null}
                  {m.status ? <span className={classes.muted}> · {words(m.status)}</span> : null}
                </Text>
                {synonym ? (
                  <Text size="sm">
                    <Anchor component={Link} to={`/taxon/${m.acceptedId}`}>Go to the accepted taxon</Anchor>
                  </Text>
                ) : null}
                <ClassificationPath items={m.classification} />
              </div>
            ) : <Text c="dimmed">This name is not matched to the Catalogue of Life.</Text>}
          </Section>

          <Section title="First appearance">
            {first ? (
              <Text>
                <Anchor component={Link} to={`/work/${first.workId}`}>{first.title || `Work ${first.workId}`}</Anchor>
                {first.publicationDate ? <span className={classes.muted}> · {formatDate(first.publicationDate)}</span> : null}
              </Text>
            ) : <Text c="dimmed">Not seen in any paper yet.</Text>}
          </Section>

          <Section title="Filter">
            <MentionedInFilter value={mentionedIn} onChange={setMentioned} />
          </Section>

          <Section title="Papers naming this name">
            {works.isError ? <Alert color="red">{(works.error as Error).message}</Alert>
              : works.data ? (works.data.results.length ? <WorkList works={works.data.results} /> : <Text c="dimmed">No papers yet.</Text>)
              : <Loader size="sm" />}
            {works.data && works.data.total > works.data.results.length ? (
              <p className={classes.more}>
                <Link to={`/?name=${encodeURIComponent(id)}${mentionedIn.map((m) => `&mentionedIn=${m}`).join('')}`}>All {works.data.total} papers</Link>
              </p>
            ) : null}
          </Section>
        </div>

        <SideColumn lede={<>
          A name as written in papers.{' '}
          <Anchor component={Link} to="/about#names" inherit>What is this?</Anchor>
        </>}>
          <Section title="Papers per year">
            <YearBars years={n.timeline} />
          </Section>
        </SideColumn>
      </div>
    </article>
  );
}
