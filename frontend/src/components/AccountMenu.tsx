import { Button, Menu } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { signOut } from '../api';
import { takeNext, useMe } from '../auth/useMe';

export function AccountMenu() {
  const me = useMe();
  const client = useQueryClient();
  const navigate = useNavigate();
  const here = useLocation();
  const out = useMutation({
    mutationFn: signOut,
    onSuccess: () => {
      client.setQueryData(['me'], null);
      client.removeQueries({ predicate: (q) => q.queryKey[0] !== 'me' && q.queryKey[0] !== 'auth-config' });
      navigate('/');
    },
  });
  const u = me.data;
  const wasSignedIn = useRef(false);
  useEffect(() => {
    const signedIn = !!u;
    if (signedIn && !wasSignedIn.current) {
      const next = takeNext();
      if (next) navigate(next);
    }
    wasSignedIn.current = signedIn;
  }, [u, navigate]);
  if (me.isPending) return null;
  if (!u) {
    const next = here.pathname + here.search;
    const to = next === '/' || next.startsWith('/signin') ? '/signin' : `/signin?next=${encodeURIComponent(next)}`;
    return <Button component={Link} to={to} variant="default" size="xs" ml="auto">Sign in</Button>;
  }
  return (
    <Menu position="bottom-end">
      <Menu.Target>
        <Button variant="default" size="xs" ml="auto">{u.displayName || u.email || 'Account'}</Button>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Item component={Link} to="/hits">My hits</Menu.Item>
        <Menu.Item component={Link} to="/following">Following</Menu.Item>
        <Menu.Item component={Link} to="/account">Account</Menu.Item>
        <Menu.Divider />
        <Menu.Item onClick={() => out.mutate()}>Sign out</Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}
