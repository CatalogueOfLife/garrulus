import { useEffect, useState } from 'react';
import { Alert, Button, Checkbox, Collapse, Group, Modal, NativeSelect, Radio, Stack, Text, TextInput, UnstyledButton } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation } from 'react-router-dom';
import {
  ApiError, createSubscription, deleteSubscription, getSubscriptions, getTaxGroups, patchSubscription,
  type Subscription, type SubscriptionFilters, type SubscriptionType,
} from '../api';
import { useMe } from '../auth/useMe';
import { ConfirmButton } from './ConfirmButton';
import { subfieldOptions } from '../subfields';

const MIN_YEAR = 1750;
const CODES = ['ZOOLOGICAL', 'BOTANICAL', 'BACTERIAL', 'VIRUS'];
const pretty = (v: string) => v.charAt(0) + v.slice(1).toLowerCase().replace(/_/g, ' ');

/** An empty string is the rolling window; else an integer MIN_YEAR..now, or null when invalid. */
export function parseSinceYear(text: string): number | null | undefined {
  const t = text.trim();
  if (!t) return undefined;
  if (!/^\d{4}$/.test(t)) return null;
  const y = Number(t);
  return y >= MIN_YEAR && y <= new Date().getFullYear() ? y : null;
}

function bodyOf(e: unknown): { error?: string; id?: number } {
  return (e instanceof ApiError && e.body && typeof e.body === 'object' ? e.body : {}) as { error?: string; id?: number };
}

interface DialogProps {
  opened: boolean;
  onClose: () => void;
  type: SubscriptionType;
  target: string;
  label: string;
  existing?: Subscription;
  /** The create call answered "exists": edit that subscription instead. */
  onExists?: (id: number) => void;
}

function Form({ onClose, type, target, label, existing, onExists }: Omit<DialogProps, 'opened'>) {
  const client = useQueryClient();
  const groups = useQuery({ queryKey: ['taxgroups'], queryFn: getTaxGroups, staleTime: Infinity });
  const mainDefault = type === 'TAXON' || type === 'TAXGROUP';
  const [main, setMain] = useState(existing?.mainSubjectOnly ?? mainDefault);
  const [newNames, setNewNames] = useState(existing?.newNamesOnly ?? false);
  const [abstracts, setAbstracts] = useState(existing?.includeAbstractOnly ?? true);
  const [refs, setRefs] = useState(existing?.includeReferences ?? false);
  const [window, setWindow] = useState<'rolling' | 'since'>(existing?.sinceYear ? 'since' : 'rolling');
  const [year, setYear] = useState(existing?.sinceYear ? String(existing.sinceYear) : '');
  const [narrow, setNarrow] = useState<SubscriptionFilters>(existing?.filters ?? {});
  const [open, setOpen] = useState(Object.values(existing?.filters ?? {}).some(Boolean));
  const [error, setError] = useState<string | null>(null);
  const [yearError, setYearError] = useState<string | null>(null);

  const done = () => {
    client.invalidateQueries({ queryKey: ['subscriptions'] });
    onClose();
  };
  const save = useMutation({
    mutationFn: async () => {
      const since = window === 'since' ? parseSinceYear(year) : undefined;
      const filters = Object.fromEntries(Object.entries(narrow).filter(([, v]) => v)) as SubscriptionFilters;
      const o = { mainSubjectOnly: main, newNamesOnly: newNames, includeAbstractOnly: abstracts, includeReferences: refs, filters };
      if (existing) return patchSubscription(existing.id, { ...o, sinceYear: since ?? null });
      return createSubscription(type, target, label, { ...o, sinceYear: since });
    },
    onSuccess: done,
    onError: async (e) => {
      const b = bodyOf(e);
      if (e instanceof ApiError && e.status === 409 && b.error === 'limit') setError('You follow 100 things already');
      else if (e instanceof ApiError && e.status === 409 && b.error === 'exists' && b.id !== undefined) {
        await client.refetchQueries({ queryKey: ['subscriptions'] });
        onExists?.(b.id);
      } else setError('Saving failed. Try again.');
    },
  });
  const remove = useMutation({
    mutationFn: () => deleteSubscription(existing!.id),
    onSuccess: done,
    onError: () => setError('Deleting failed. Try again.'),
  });

  const submit = () => {
    setError(null);
    if (window === 'since' && parseSinceYear(year) == null) {
      setYearError(`Enter a year between ${MIN_YEAR} and ${new Date().getFullYear()}`);
      return;
    }
    setYearError(null);
    save.mutate();
  };
  const set = (k: keyof SubscriptionFilters, v: string) => setNarrow((n) => ({ ...n, [k]: v || undefined }));

  return (
    <Stack gap="sm">
      <Text size="sm" c="dimmed">{label}</Text>
      <Checkbox label="Only main subject" checked={main} onChange={(e) => setMain(e.currentTarget.checked)} />
      <Checkbox label="Only new names" checked={newNames} onChange={(e) => setNewNames(e.currentTarget.checked)} />
      <Checkbox label="Include papers with an abstract only" checked={abstracts} onChange={(e) => setAbstracts(e.currentTarget.checked)} />
      <Checkbox label="Include papers that only cite the name in references" checked={refs} onChange={(e) => setRefs(e.currentTarget.checked)} />
      <Radio.Group label="Papers published" value={window} onChange={(v) => setWindow(v as 'rolling' | 'since')}>
        <Stack gap={4} mt={4}>
          <Radio value="rolling" label="Last 90 days" />
          <Radio value="since" label="Since year" />
        </Stack>
      </Radio.Group>
      {window === 'since' ? (
        <TextInput
          aria-label="Year" placeholder={String(new Date().getFullYear())} inputMode="numeric" maw={120}
          value={year} error={yearError}
          onChange={(e) => setYear(e.currentTarget.value.replace(/\D/g, '').slice(0, 4))}
        />
      ) : null}
      <UnstyledButton onClick={() => setOpen((o) => !o)} aria-expanded={open}>
        <Text size="sm" fw={500}>{open ? '▾' : '▸'} Narrow down</Text>
      </UnstyledButton>
      <Collapse in={open}>
        <Stack gap="xs">
          <NativeSelect label="Nomenclatural code" value={narrow.code ?? ''}
            data={[{ value: '', label: 'Any' }, ...CODES.map((c) => ({ value: c, label: pretty(c) }))]}
            onChange={(e) => set('code', e.currentTarget.value)} />
          <NativeSelect label="Taxonomic group" value={narrow.taxgroup ?? ''}
            data={[{ value: '', label: 'Any' }, ...(groups.data ?? []).map((g) => ({ value: g.name, label: g.name }))]}
            onChange={(e) => set('taxgroup', e.currentTarget.value)} />
          <NativeSelect label="Journal type" value={narrow.journalType ?? ''}
            data={[{ value: '', label: 'Any' }, { value: 'TAXONOMIC', label: 'Taxonomic' }, { value: 'GENERAL', label: 'General' }]}
            onChange={(e) => set('journalType', e.currentTarget.value)} />
          <NativeSelect label="Subfield" value={narrow.subfield ?? ''} onChange={(e) => set('subfield', e.currentTarget.value)}>
            {subfieldOptions(narrow.subfield).map((o) => <option key={o.value} value={o.value} title={o.value || undefined}>{o.label}</option>)}
          </NativeSelect>
        </Stack>
      </Collapse>
      {error ? <Alert color="red">{error}</Alert> : null}
      <Group justify="space-between" mt="xs">
        {existing ? (
          <ConfirmButton variant="outline" color="red" question={`Stop following ${label}?`} confirmLabel="Yes, unfollow"
            onConfirm={() => remove.mutate()} loading={remove.isPending}>
            Unfollow
          </ConfirmButton>
        ) : <span />}
        <Group gap="xs">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button onClick={submit} loading={save.isPending}>Save</Button>
        </Group>
      </Group>
    </Stack>
  );
}

/** The follow dialog: creates a subscription, or edits (and deletes) the existing one. */
export function FollowDialog(props: DialogProps) {
  const { opened, onClose, existing, label } = props;
  return (
    <Modal opened={opened} onClose={onClose} title={existing ? `Following ${label}` : `Follow ${label}`}>
      <Form key={existing?.id ?? 'new'} {...props} />
    </Modal>
  );
}

export function FollowButton({ type, target, label }: { type: SubscriptionType; target: string; label: string }) {
  const me = useMe();
  const here = useLocation();
  const [opened, setOpened] = useState(false);
  const [editId, setEditId] = useState<number | null>(null);
  const signedIn = !!me.data;
  const subs = useQuery({ queryKey: ['subscriptions'], queryFn: getSubscriptions, enabled: signedIn });
  useEffect(() => setEditId(null), [type, target]);
  const existing = subs.data?.find((s) => s.id === editId) ?? subs.data?.find((s) => s.type === type && s.target === target);

  if (me.isPending) return null;
  if (!signedIn) {
    const next = here.pathname + here.search;
    return <Button component={Link} to={`/signin?next=${encodeURIComponent(next)}`} variant="default" size="xs">Follow</Button>;
  }
  return (
    <>
      <Button variant={existing ? 'filled' : 'default'} size="xs" onClick={() => setOpened(true)}>
        {existing ? 'Following' : 'Follow'}
      </Button>
      <FollowDialog
        opened={opened} onClose={() => setOpened(false)} type={type} target={target} label={label}
        existing={existing} onExists={setEditId}
      />
    </>
  );
}
