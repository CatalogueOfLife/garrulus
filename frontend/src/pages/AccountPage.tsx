import { Alert, Button, Group, Modal, Select, Stack, Text, TextInput } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { deleteMe, downloadExport, patchMe, requestEmailChange, type DigestFrequency } from '../api';
import { useMe } from '../auth/useMe';
import styles from '../ui/pages.module.css';
import { usePageTitle } from '../usePageTitle';

const FREQ = [{ value: 'DAILY', label: 'Daily' }, { value: 'WEEKLY', label: 'Weekly' }, { value: 'OFF', label: 'Off' }];
const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
  .map((label, i) => ({ value: String(i + 1), label }));
const HOURS = Array.from({ length: 24 }, (_, h) => ({ value: String(h), label: `${String(h).padStart(2, '0')}:00` }));

function zones(): string[] {
  try {
    return (Intl as unknown as { supportedValuesOf(k: string): string[] }).supportedValuesOf('timeZone');
  } catch {
    return [];
  }
}
const browserZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

export function AccountPage() {
  usePageTitle('Account');
  const me = useMe();
  const client = useQueryClient();
  const navigate = useNavigate();
  const u = me.data;

  const [email, setEmail] = useState('');
  const [zone, setZone] = useState<string>('');
  const [freq, setFreq] = useState<string>('DAILY');
  const [day, setDay] = useState<string>('1');
  const [hour, setHour] = useState<string>('7');
  const [confirm, setConfirm] = useState(false);
  const [typed, setTyped] = useState('');

  useEffect(() => {
    if (!u) return;
    setEmail(u.email ?? '');
    setZone(u.timeZone || browserZone());
    setFreq(u.digestFrequency);
    setDay(String(u.digestWeekday));
    setHour(String(u.digestHour));
  }, [u]);

  const zoneOptions = useMemo(() => {
    const all = zones();
    return zone && !all.includes(zone) ? [zone, ...all] : all;
  }, [zone]);

  const save = useMutation({
    mutationFn: () => patchMe({
      timeZone: zone, digestFrequency: freq as DigestFrequency, digestWeekday: Number(day), digestHour: Number(hour),
    }),
    onSuccess: (m) => client.setQueryData(['me'], m),
  });
  const change = useMutation({ mutationFn: () => requestEmailChange(email.trim()) });
  const remove = useMutation({
    mutationFn: deleteMe,
    onSuccess: () => {
      client.setQueryData(['me'], null);
      navigate('/');
    },
  });
  const exp = useMutation({ mutationFn: downloadExport });

  if (me.isPending) return null;
  if (!u) {
    return (
      <div className={styles.narrow}>
        <h1 className={styles.narrowTitle}>Your account</h1>
        <Text>Sign in to manage your Garrulus account.</Text>
        <Button component={Link} to="/signin?next=/account" variant="filled">Sign in</Button>
      </div>
    );
  }

  return (
    <div className={styles.accountCol}>
      <h1 className={styles.pageTitle}>Your account</h1>
      {u.orcid && <Text size="sm" c="dimmed">Signed in with ORCID iD {u.orcid}</Text>}

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Email</h2>
        {!u.email && <Alert color="gray">Add an email address to receive digests.</Alert>}
        <TextInput label="Email" type="email" value={email} onChange={(e) => setEmail(e.currentTarget.value)} />
        <Group>
          <Button loading={change.isPending} disabled={!email.trim() || email.trim() === (u.email ?? '')}
            onClick={() => change.mutate()}>
            {u.email ? 'Change email' : 'Add email'}
          </Button>
        </Group>
        {change.isSuccess && <Text size="sm">We sent a link to that address. The change takes effect when you open it.</Text>}
        {change.isError && <p className={styles.error}>That did not work. Check the address and try again.</p>}
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Digest emails</h2>
        <Select label="Time zone" searchable data={zoneOptions} value={zone} onChange={(v) => v && setZone(v)} allowDeselect={false} />
        <Select label="Digest frequency" data={FREQ} value={freq} onChange={(v) => v && setFreq(v)} allowDeselect={false} />
        {freq === 'WEEKLY' && (
          <Select label="Weekday" data={DAYS} value={day} onChange={(v) => v && setDay(v)} allowDeselect={false} />
        )}
        <Select label="Hour (local time)" data={HOURS} value={hour} onChange={(v) => v && setHour(v)} allowDeselect={false} />
        <Group>
          <Button variant="filled" loading={save.isPending} onClick={() => save.mutate()}>Save settings</Button>
          {save.isSuccess && <Text size="sm">Settings saved</Text>}
          {save.isError && <p className={styles.error}>Could not save the settings.</p>}
        </Group>
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Your data</h2>
        <Group>
          <Button loading={exp.isPending} onClick={() => exp.mutate()}>Download my data</Button>
          <Button color="red" variant="outline" onClick={() => setConfirm(true)}>Delete account</Button>
        </Group>
      </section>

      <Modal opened={confirm} onClose={() => { setConfirm(false); setTyped(''); }} title="Delete your account?">
        <Stack>
          <Text size="sm">This removes your account, your follows, your hits and your digests for good.</Text>
          <TextInput label="Type DELETE to confirm" value={typed} onChange={(e) => setTyped(e.currentTarget.value)} />
          {remove.isError && <p className={styles.error}>Could not delete the account.</p>}
          <Button color="red" variant="outline" disabled={typed !== 'DELETE'} loading={remove.isPending} onClick={() => remove.mutate()}>
            Delete my account
          </Button>
        </Stack>
      </Modal>
    </div>
  );
}
