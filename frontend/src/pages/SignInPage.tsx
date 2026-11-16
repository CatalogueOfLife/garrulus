import { Alert, Anchor, Button, Divider, Stack, Text, TextInput, Title } from '@mantine/core';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { getAuthConfig, requestSignInLink } from '../api';
import { rememberNext } from '../auth/useMe';
import { usePageTitle } from '../usePageTitle';

export function SignInPage() {
  usePageTitle('Sign in');
  const [params] = useSearchParams();
  const config = useQuery({ queryKey: ['auth-config'], queryFn: getAuthConfig, staleTime: Infinity });
  const [email, setEmail] = useState('');
  const link = useMutation({ mutationFn: requestSignInLink });
  const orcidHref = '/oauth2/authorization/orcid';

  const submit = (e: FormEvent) => {
    e.preventDefault();
    rememberNext(params.get('next'));
    link.mutate(email.trim());
  };

  return (
    <Stack maw={420} mx="auto" mt="xl">
      <Title order={2}>Sign in to Garrulus</Title>
      {params.get('error') === 'orcid' && (
        <Alert color="red">ORCID sign-in did not work. Try again, or use your email address.</Alert>
      )}
      {config.data?.orcid && (
        <>
          <Button component="a" href={orcidHref} onClick={() => rememberNext(params.get('next'))} variant="default">Continue with ORCID</Button>
          <Divider label="or" />
        </>
      )}
      {link.isSuccess ? (
        <Alert title="Check your inbox">
          If the address is valid, a sign-in link is on its way. It works once, for 15 minutes.
        </Alert>
      ) : (
        <form onSubmit={submit}>
          <Stack>
            <TextInput label="Email" type="email" required value={email} onChange={(e) => setEmail(e.currentTarget.value)} />
            {link.isError && <Text c="red" size="sm">That did not work. Try again in a moment.</Text>}
            <Button type="submit" loading={link.isPending}>Email me a sign-in link</Button>
            <Text size="xs" c="dimmed">
              No password. <Anchor href="/" size="xs">Back to search</Anchor>
            </Text>
          </Stack>
        </form>
      )}
    </Stack>
  );
}
