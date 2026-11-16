import { useState } from 'react';
import { Alert, Anchor, Badge, Button, Group, Loader, Stack, Switch, Text, Title } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { deleteSubscription, getSubscriptions, patchSubscription, type Subscription } from '../api';
import { useMe } from '../auth/useMe';
import { FollowDialog } from '../components/FollowButton';
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
    <Stack gap="md">
      <Title order={2}>Following</Title>
      {subs.isError ? <Alert color="red">{(subs.error as Error).message}</Alert>
        : !subs.data ? <Loader size="sm" />
        : subs.data.length === 0 ? <Text c="dimmed">You follow nothing yet. Use the Follow button on a taxon, journal or name page.</Text>
        : (
          <Stack component="ul" gap="md" p={0} m={0} style={{ listStyle: 'none' }}>
            {subs.data.map((s) => (
              <li key={s.id}>
                <Group justify="space-between" align="flex-start" wrap="wrap">
                  <div>
                    <Group gap="xs">
                      <Anchor component={Link} to={targetPath(s)} fw={600}>{s.label}</Anchor>
                      <Badge variant="light" size="sm">{TYPE_LABEL[s.type]}</Badge>
                    </Group>
                    <Anchor component={Link} to={`/hits?subscription=${s.id}`} size="sm">Hits</Anchor>
                  </div>
                  <Group gap="xs">
                    <Switch label="Paused" checked={s.paused} onChange={() => pause.mutate(s)} />
                    <Button size="xs" variant="default" onClick={() => setEditing(s)}>Edit</Button>
                    <Button size="xs" variant="default" onClick={() => copy(s)}>Copy feed URL</Button>
                    <Button size="xs" variant="subtle" color="red" onClick={() => remove.mutate(s)}>Delete</Button>
                    {copied === s.id ? <Text size="sm" c="dimmed">Copied</Text> : null}
                  </Group>
                </Group>
              </li>
            ))}
          </Stack>
        )}
      {editing ? (
        <FollowDialog
          opened onClose={() => setEditing(null)} type={editing.type} target={editing.target} label={editing.label}
          existing={subs.data?.find((s) => s.id === editing.id) ?? editing}
        />
      ) : null}
    </Stack>
  );
}
