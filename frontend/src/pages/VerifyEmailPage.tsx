import { Alert, Button, Stack, Text, Title } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError, getAuthConfig, verifyEmail } from '../api';
import { usePageTitle } from '../usePageTitle';

export function VerifyEmailPage() {
  usePageTitle('Confirm email');
  const token = useSearchParams()[0].get('token') ?? '';
  const client = useQueryClient();
  // the first call to /api/auth/** issues the XSRF cookie that the post needs
  const ready = useQuery({ queryKey: ['auth-config'], queryFn: getAuthConfig, staleTime: Infinity });
  const m = useMutation({
    mutationFn: () => verifyEmail(token),
    onSuccess: () => client.invalidateQueries({ queryKey: ['me'] }),
  });
  const outcome = m.data?.outcome;
  return (
    <Stack maw={460} mx="auto" mt="xl">
      <Title order={2}>Confirm your email address</Title>
      {outcome === 'SET' && <Alert color="green" title="Address confirmed">Your address is set. <Link to="/account">Go to your account.</Link></Alert>}
      {outcome === 'MERGED' && (
        <Alert color="green" title="Your accounts were merged">The address belonged to an earlier account of yours; both are now one. <Link to="/account">Go to your account.</Link></Alert>
      )}
      {outcome === 'REFUSED_OTHER_ORCID' && (
        <Alert color="red" title="Address not added">
          This address belongs to an account that is linked to another ORCID iD, so it cannot be added to this one.
        </Alert>
      )}
      {m.isError && (
        <Alert color="red" title="Link not accepted">
          {m.error instanceof ApiError && m.error.status === 403
            ? <>Something went wrong with the request. Please reload the page and try again.</>
            : m.error instanceof ApiError && m.error.status === 401
            ? <>You are signed in as another account. <Link to="/signin">Sign in again</Link> with the account that asked for this address.</>
            : <>This link is invalid or has expired. Request a new one from your <Link to="/account">account page</Link>.</>}
        </Alert>
      )}
      {!outcome && !m.isError && (
        <>
          <Text>Press the button to confirm that this address is yours.</Text>
          <Button onClick={() => m.mutate()} loading={m.isPending} disabled={!token || ready.isPending}>Confirm this address</Button>
        </>
      )}
    </Stack>
  );
}
