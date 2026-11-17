import { Alert, Button, Text } from '@mantine/core';
import { useMutation } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { unsubscribe } from '../api';
import styles from '../ui/pages.module.css';
import { usePageTitle } from '../usePageTitle';

export function UnsubscribePage() {
  usePageTitle('Unsubscribe');
  const t = useSearchParams()[0].get('t') ?? '';
  const m = useMutation({ mutationFn: () => unsubscribe(t) });
  return (
    <div className={styles.narrow}>
      <h1 className={styles.narrowTitle}>Garrulus digest emails</h1>
      {m.isSuccess ? (
        <Alert color="green">
          Digest emails are off. Your follows are kept; you can turn digests on again in your <Link to="/account">account settings</Link>.
        </Alert>
      ) : (
        <>
          <Text>Stop the digest emails sent to this address?</Text>
          {m.isError && <p className={styles.error}>This link is not valid. Use the unsubscribe link of a recent email, or your account settings.</p>}
          <Button variant="filled" onClick={() => m.mutate()} loading={m.isPending} disabled={!t}>Turn off digest emails</Button>
        </>
      )}
    </div>
  );
}
