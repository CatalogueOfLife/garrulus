import { Alert, Button, Stack, Text, Title } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { getAuthConfig, signInWithToken } from '../api';
import { takeNext } from '../auth/useMe';
import { usePageTitle } from '../usePageTitle';

/** The token is posted only on an explicit click, never on load (a mail scanner or a forged link must not sign anyone in). */
export function SignInVerifyPage() {
  usePageTitle('Sign in');
  const token = useSearchParams()[0].get('token') ?? '';
  const navigate = useNavigate();
  const client = useQueryClient();
  // the first call to /api/auth/** issues the XSRF cookie that the token post needs
  const ready = useQuery({ queryKey: ['auth-config'], queryFn: getAuthConfig, staleTime: Infinity });
  const m = useMutation({
    mutationFn: () => signInWithToken(token),
    onSuccess: async () => {
      const next = takeNext() ?? '/'; // taken first, so the app-wide handler does not also act on it
      await client.invalidateQueries({ queryKey: ['me'] });
      navigate(next);
    },
  });
  return (
    <Stack maw={420} mx="auto" mt="xl">
      <Title order={2}>Sign in to Garrulus</Title>
      {m.isError ? (
        <Alert color="red" title="Link not accepted">
          This sign-in link is invalid or has expired. <Link to="/signin">Request a new one.</Link>
        </Alert>
      ) : (
        <>
          <Text>Press the button to finish signing in on this device.</Text>
          <Button onClick={() => m.mutate()} loading={m.isPending} disabled={!token || ready.isPending}>Sign in to Garrulus</Button>
        </>
      )}
    </Stack>
  );
}
