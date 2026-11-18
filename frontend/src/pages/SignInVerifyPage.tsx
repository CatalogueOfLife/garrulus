import { Alert, Anchor, Button, Text } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { getAuthConfig, signInWithToken } from '../api';
import { takeNext } from '../auth/useMe';
import styles from '../ui/pages.module.css';
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
    <div className={styles.narrow}>
      <h1 className={styles.narrowTitle}>Sign in to Garrulus</h1>
      {m.isError ? (
        <Alert color="red" title="Link not accepted">
          This sign-in link is invalid or has expired. <Anchor component={Link} to="/signin">Request a new one.</Anchor>
        </Alert>
      ) : (
        <>
          <Text>Press the button to finish signing in on this device.</Text>
          <Button variant="filled" onClick={() => m.mutate()} loading={m.isPending} disabled={!token || ready.isPending}>Sign in to Garrulus</Button>
        </>
      )}
    </div>
  );
}
