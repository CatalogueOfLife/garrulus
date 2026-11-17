import { useState } from 'react';
import { Alert, Anchor, Button, Loader, Switch, Text } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { deleteSubscription, getSubscriptions, newFeedToken, patchSubscription, type Subscription } from '../api';
import { useMe } from '../auth/useMe';
import { ConfirmButton } from '../components/ConfirmButton';
import { FollowDialog } from '../components/FollowButton';
import { EmptyState } from '../ui/EmptyState';
import styles from '../ui/pages.module.css';
import { usePageTitle } from '../usePageTitle';

const TYPE_LABEL: Record<Subscription['type'], string> = { TAXON: 'Taxon', SOURCE: 'Journal', NAME: 'Name', TAXGROUP: 'Group' };
const targetPath = (s: Subscription) => ({
  TAXON: `/taxon/${encodeURIComponent(s.target)}`,
  SOURCE: `/source/${encodeURIComponent(s.target)}`,
  NAME: `/name/${encodeURIComponent(s.target)}`,
  TAXGROUP: `/?taxgroup=${encodeURIComponent(s.target)}`,
})[s.type];

export function FollowingPage() {
  usePageTitle('Following');
  const me = useMe();
  const client = useQueryClient();
  const subs = useQuery({ queryKey: ['subscriptions'], queryFn: getSubscriptions, enabled: !!me.data });
  const [editing, setEditing] = useState<Subscription | null>(null);
  const [copied, setCopied] = useState<number | null>(null);
  const refresh = () => client.invalidateQueries({ queryKey: ['subscriptions'] });
  const pause = useMutation({ mutationFn: (s: Subscription) => patchSubscription(s.id, { paused: !s.paused }), onSuccess: refresh });
  const remove = useMutation({ mutationFn: (s: Subscription) => deleteSubscription(s.id), onSuccess: refresh });
  // ids whose feed URL was just replaced: the new one is shown until the page is left
  const [renewed, setRenewed] = useState<Set<number>>(new Set());
  const renew = useMutation({
    mutationFn: (s: Subscription) => newFeedToken(s.id),
    onSuccess: (fresh) => {
      client.setQueryData<Subscription[]>(['subscriptions'], (old) => old?.map((s) => (s.id === fresh.id ? fresh : s)));
      setRenewed((r) => new Set(r).add(fresh.id));
      setCopied(null);
      refresh();
    },
  });

  if (me.isPending) return <Loader size="sm" />;
  if (!me.data) {
    return <Text>Please <Anchor component={Link} to={`/signin?next=${encodeURIComponent('/following')}`}>sign in</Anchor> to see what you follow.</Text>;
  }
  const copy = async (s: Subscription) => {
    try {
      await navigator.clipboard.writeText(s.feedUrl);
      setCopied(s.id);
    } catch { /* clipboard unavailable */ }
  };

  return (
    <div>
      <h1 className={styles.pageTitle}>Following</h1>
      {subs.isError ? <Alert color="red">{(subs.error as Error).message}</Alert>
        : !subs.data ? <Loader size="sm" />
        : subs.data.length === 0 ? (
          <EmptyState title="You follow nothing yet">
            Use the Follow button on a taxon, journal or name page.
          </EmptyState>
        )
        : (
          <ul className={styles.ruled}>
            {subs.data.map((s) => (
              <li key={s.id}>
                <div className={styles.rowLine}>
                  <div>
                    <Anchor component={Link} to={targetPath(s)} className={styles.rowTitle}>{s.label}</Anchor>
                    <p className={styles.rowMeta}>
                      <span>{TYPE_LABEL[s.type]}</span> · <Anchor component={Link} to={`/hits?subscription=${s.id}`} inherit>Hits</Anchor>
                    </p>
                  </div>
                  <Switch label="Paused" checked={s.paused} onChange={() => pause.mutate(s)} />
                </div>
                {s.targetStatus === 'MOVED' && s.xrNote ? (
                  <Alert color="gray" mt="xs" role="note" title="This taxon moved">{s.xrNote}</Alert>
                ) : null}
                {s.targetStatus === 'MISSING' ? (
                  <Alert color="gray" mt="xs" role="note" title="This taxon is no longer in the catalogue">
                    {s.xrNote ?? 'It is missing from the latest Catalogue of Life.'}{' '}
                    You can remove this follow with Delete.
                  </Alert>
                ) : null}
                {renewed.has(s.id) ? (
                  <Text size="sm" c="dimmed">New feed URL: <Text span size="sm" ff="monospace">{s.feedUrl}</Text></Text>
                ) : null}
                <div className={styles.actions}>
                  <Button size="xs" onClick={() => setEditing(s)}>Edit</Button>
                  <Button size="xs" onClick={() => copy(s)}>Copy feed URL</Button>
                  <ConfirmButton size="xs" question="The old feed URL stops working."
                    confirmLabel="Make a new URL" loading={renew.isPending && renew.variables?.id === s.id}
                    onConfirm={() => renew.mutate(s)}>
                    New feed URL
                  </ConfirmButton>
                  <ConfirmButton size="xs" variant="outline" color="red" question="Delete this follow?"
                    confirmLabel="Yes, delete" onConfirm={() => remove.mutate(s)}>
                    Delete
                  </ConfirmButton>
                  {copied === s.id ? <Text size="sm" c="dimmed">Copied</Text> : null}
                </div>
              </li>
            ))}
          </ul>
        )}
      {editing ? (
        <FollowDialog
          opened onClose={() => setEditing(null)} type={editing.type} target={editing.target} label={editing.label}
          existing={subs.data?.find((s) => s.id === editing.id) ?? editing}
        />
      ) : null}
    </div>
  );
}
