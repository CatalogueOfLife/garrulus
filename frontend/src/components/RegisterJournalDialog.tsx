import { useEffect, useRef, useState } from 'react';
import { Alert, Anchor, Button, Group, Modal, SegmentedControl, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  ApiError, getRegistration, registerJournal, withdrawRegistration, type Registration, type RegistrationKind,
} from '../api';

const POLL_MS = 2000;
const POLL_LIMIT_MS = 30_000;

const INPUT: Record<RegistrationKind, { label: string; placeholder: string; field: 'issn' | 'doi' | 'url' }> = {
  ISSN: { label: 'ISSN', placeholder: '1175-5326', field: 'issn' },
  DOI: { label: 'Article DOI', placeholder: '10.11646/zootaxa.5200.1.1', field: 'doi' },
  URL: { label: 'Web address', placeholder: 'https://', field: 'url' },
};

const NOT_IDENTIFIED: Record<string, string> = {
  DOI_UNKNOWN: 'That DOI is not known to Crossref or OpenAlex, or it names no journal.',
  ADDRESS_NOT_PUBLIC: 'That address is not a public web page.',
  ADDRESS_UNKNOWN: 'The host name of that address does not exist.',
  PAGE_UNREACHABLE: 'The page could not be read.',
  NO_ISSN_FOUND: 'The page names no ISSN.',
  LOOKUP_FAILED: 'The lookup failed. Registering again may help.',
};

export const notIdentifiedText = (reason?: string | null) =>
  (reason && NOT_IDENTIFIED[reason]) || 'Garrulus could not identify the journal.';

export const STATUS_TEXT: Record<Registration['status'], string> = {
  IDENTIFYING: 'Identifying',
  COVERED: 'Covered by Garrulus',
  CANDIDATE: 'Candidate, waits for an editor',
  DECLINED: 'Not followed',
  CEASED: 'Ceased publication',
  NOT_IDENTIFIED: 'Not identified',
  WITHDRAWN: 'Withdrawn',
};

function Result({ r, onDone, onWithdraw, busy }: { r: Registration; onDone: () => void; onWithdraw: () => void; busy: boolean }) {
  const j = r.journal;
  if (r.state === 'NOT_IDENTIFIED') {
    return (
      <Stack gap="sm">
        <Alert color="gray" title="Not identified">{notIdentifiedText(r.reason)}</Alert>
        <Group justify="flex-end"><Button variant="filled" onClick={onDone}>Done</Button></Group>
      </Stack>
    );
  }
  return (
    <Stack gap="sm">
      <div>
        <Text fw={500}>{j?.title ?? r.input}</Text>
        {j?.issns.length ? <Text size="sm" c="dimmed">ISSN {j.issns.join(', ')}</Text> : null}
        {j?.publisher ? <Text size="sm" c="dimmed">{j.publisher}</Text> : null}
        <Text size="sm" mt={4}>{STATUS_TEXT[r.status]}</Text>
        {j && (j.tier === 'CORE' || j.tier === 'BROAD') ? (
          <Anchor component={Link} to={`/source/${j.id}`} size="sm">Open the journal</Anchor>
        ) : null}
      </div>
      <Group justify="flex-end">
        <Button variant="default" onClick={onWithdraw} loading={busy}>Not this journal</Button>
        <Button variant="filled" onClick={onDone}>Done</Button>
      </Group>
    </Stack>
  );
}

function Form({ onClose }: { onClose: () => void }) {
  const client = useQueryClient();
  const [kind, setKind] = useState<RegistrationKind>('ISSN');
  const [value, setValue] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [id, setId] = useState<number | null>(null);
  const [result, setResult] = useState<Registration | null>(null);
  const [slow, setSlow] = useState(false);
  const alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []);

  // poll the registration every 2 s for at most 30 s
  useEffect(() => {
    if (id === null) return;
    const started = Date.now();
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      try {
        const r = await getRegistration(id);
        if (!alive.current) return;
        if (r.state !== 'IDENTIFYING') {
          setResult(r);
          client.invalidateQueries({ queryKey: ['registrations'] });
          return;
        }
      } catch { /* try again until the time is up */ }
      if (!alive.current) return;
      if (Date.now() - started >= POLL_LIMIT_MS) {
        setSlow(true);
        client.invalidateQueries({ queryKey: ['registrations'] });
        return;
      }
      timer = setTimeout(tick, POLL_MS);
    };
    timer = setTimeout(tick, POLL_MS);
    return () => clearTimeout(timer);
  }, [id, client]);

  const submit = async () => {
    const v = value.trim();
    if (!v) return;
    setError(null);
    setBusy(true);
    try {
      const body: { issn?: string; doi?: string; url?: string; note?: string } = { [INPUT[kind].field]: v };
      if (note.trim()) body.note = note.trim();
      const { id: newId } = await registerJournal(body);
      setId(newId);
    } catch (e) {
      const msg = e instanceof ApiError && e.body && typeof e.body === 'object' && 'error' in e.body ? String((e.body as { error: unknown }).error) : null;
      if (e instanceof ApiError && e.status === 429) setError('You have registered many journals today. Try again tomorrow.');
      else if (e instanceof ApiError && e.status === 413) setError('That is too much text.');
      else setError(msg ?? 'Registering failed. Try again.');
    } finally {
      setBusy(false);
    }
  };

  const withdraw = async () => {
    if (id === null) return;
    setBusy(true);
    try {
      await withdrawRegistration(id);
      client.invalidateQueries({ queryKey: ['registrations'] });
      onClose();
    } catch {
      setError('Withdrawing failed. Try again.');
      setBusy(false);
    }
  };

  if (result) return <Result r={result} onDone={onClose} onWithdraw={withdraw} busy={busy} />;
  if (id !== null) {
    return (
      <Stack gap="sm">
        {slow ? (
          <Text>Still identifying. The result will appear under Registered journals on your account page.</Text>
        ) : <Text role="status">Identifying the journal…</Text>}
        {error ? <Alert color="red">{error}</Alert> : null}
        <Group justify="flex-end">
          {slow ? <Button variant="default" onClick={withdraw} loading={busy}>Withdraw</Button> : null}
          <Button variant="filled" onClick={onClose}>Done</Button>
        </Group>
      </Stack>
    );
  }
  return (
    <form onSubmit={(e) => { e.preventDefault(); void submit(); }}>
      <Stack gap="sm">
        <Text size="sm" c="dimmed">
          Tell Garrulus about a journal it misses. Nothing is followed until an editor accepts it.
        </Text>
        <SegmentedControl aria-label="What you know" value={kind} onChange={(v) => { setKind(v as RegistrationKind); setValue(''); }}
          data={[{ value: 'ISSN', label: 'ISSN' }, { value: 'DOI', label: 'Article DOI' }, { value: 'URL', label: 'Web address' }]} />
        <TextInput label={INPUT[kind].label} placeholder={INPUT[kind].placeholder} value={value} maxLength={kind === 'URL' ? 2000 : 300}
          onChange={(e) => setValue(e.currentTarget.value)} data-autofocus />
        <Textarea label="Note (optional)" value={note} maxLength={500} autosize minRows={2}
          onChange={(e) => setNote(e.currentTarget.value)} />
        {error ? <Alert color="red">{error}</Alert> : null}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="filled" loading={busy} disabled={!value.trim()}>Register</Button>
        </Group>
      </Stack>
    </form>
  );
}

/** The "Register a journal" button of the journal list and its dialog. */
export function RegisterJournalDialog({ signedIn }: { signedIn: boolean }) {
  const [opened, setOpened] = useState(false);
  if (!signedIn) {
    return <Button component={Link} to={`/signin?next=${encodeURIComponent('/journals')}`} variant="default" size="xs">Register a journal</Button>;
  }
  return (
    <>
      <Button variant="default" size="xs" onClick={() => setOpened(true)}>Register a journal</Button>
      <Modal opened={opened} onClose={() => setOpened(false)} title="Register a journal">
        <Form onClose={() => setOpened(false)} />
      </Modal>
    </>
  );
}
