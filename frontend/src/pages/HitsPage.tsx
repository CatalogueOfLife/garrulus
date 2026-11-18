import { Alert, Anchor, Button, Loader, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { getHits, type HitName } from '../api';
import { useMe } from '../auth/useMe';
import { WorkList } from '../components/WorkList';
import { shownHitNames } from '../hitNames';
import { EmptyState } from '../ui/EmptyState';
import { SciName } from '../ui/SciName';
import styles from '../ui/pages.module.css';
import { usePageTitle } from '../usePageTitle';

const PAGE_SIZE = 20;

/** The hit's names, each with the markers of its acts (sp. nov., comb. nov., ...), at most ten. */
function HitNames({ names }: { names: HitName[] }) {
  const shown = shownHitNames(names);
  if (!shown.names.length) return null;
  return (
    <ul className={styles.names} aria-label="Names of this hit">
      {shown.names.map((n) => (
        <li key={n.name}><SciName name={n.name} acts={n.acts} /></li>
      ))}
      {shown.more ? <li className={styles.rowMeta}>+{shown.more} more</li> : null}
    </ul>
  );
}

export function HitsPage() {
  usePageTitle('My hits');
  const me = useMe();
  const [params, setParams] = useSearchParams();
  const subscription = params.get('subscription') ?? undefined;
  const offset = Math.max(0, Number(params.get('offset')) || 0);
  const hits = useQuery({
    queryKey: ['hits', subscription ?? null, offset],
    queryFn: () => getHits({ subscription, limit: PAGE_SIZE, offset }),
    enabled: !!me.data,
  });

  if (me.isPending) return <Loader size="sm" />;
  if (!me.data) {
    return <Text>Please <Anchor component={Link} to={`/signin?next=${encodeURIComponent('/hits' + (params.size ? `?${params}` : ''))}`}>sign in</Anchor> to see your hits.</Text>;
  }
  const go = (o: number) => {
    const p = new URLSearchParams(params);
    if (o > 0) p.set('offset', String(o)); else p.delete('offset');
    setParams(p);
  };
  const results = hits.data?.results ?? [];

  return (
    <div>
      <h1 className={styles.pageTitle}>My hits</h1>
      {subscription ? <p className={styles.count}><Anchor component={Link} to="/hits" inherit>Show all hits</Anchor></p> : null}
      {hits.isError ? <Alert color="red">{(hits.error as Error).message}</Alert>
        : !hits.data ? <Loader size="sm" />
        : results.length === 0 ? (
          <EmptyState title={offset ? 'No more hits' : 'No hits yet'}>
            {offset ? 'There is nothing older.' : 'New papers about what you follow show up here.'}
          </EmptyState>
        )
        : (
          <ul className={styles.ruled}>
            {results.map((h) => (
              <li key={h.id}>
                <p className={styles.rowMeta}>
                  {h.kind === 'NEW_NAMES' ? <><span className={styles.newNames}>New names</span> · </> : null}
                  {h.created.slice(0, 10)}
                  {h.subscriptionLabel ? <> · <Anchor component={Link} to={`/hits?subscription=${h.subscriptionId}`} inherit>{h.subscriptionLabel}</Anchor></> : null}
                </p>
                <HitNames names={h.names ?? []} />
                {h.work ? <WorkList works={[h.work]} headingLevel={2} /> : <Text size="sm" c="dimmed">This paper is no longer listed.</Text>}
              </li>
            ))}
          </ul>
        )}
      <div className={styles.actions}>
        {offset > 0 ? <Button size="xs" onClick={() => go(Math.max(0, offset - PAGE_SIZE))}>Newer</Button> : null}
        {results.length === PAGE_SIZE ? <Button size="xs" onClick={() => go(offset + PAGE_SIZE)}>Older</Button> : null}
      </div>
    </div>
  );
}
