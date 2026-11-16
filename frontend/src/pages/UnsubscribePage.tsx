import { Alert, Button, Stack, Text, Title } from '@mantine/core';
import { useMutation } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { unsubscribe } from '../api';
import { usePageTitle } from '../usePageTitle';

export function UnsubscribePage() {
  usePageTitle('Unsubscribe');
  const t = useSearchParams()[0].get('t') ?? '';
  const m = useMutation({ mutationFn: () => unsubscribe(t) });
  return (
    <Stack maw={460} mx="auto" mt="xl">
      <Title order={2}>Garrulus digest emails</Title>
      {m.isSuccess ? (
        <Alert color="green">
          Digest emails are off. Your follows are kept; you can turn digests on again in your <Link to="/account">account settings</Link>.
        </Alert>
      ) : (
        <>
          <Text>Stop the digest emails sent to this address?</Text>
          {m.isError && <Text c="red" size="sm">This link is not valid. Use the unsubscribe link of a recent email, or your account settings.</Text>}
          <Button onClick={() => m.mutate()} loading={m.isPending} disabled={!t}>Turn off digest emails</Button>
        </>
      )}
    </Stack>
  );
}
