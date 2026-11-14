import { AppShell, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Brand } from './components/Brand';

async function ping(): Promise<string> {
  const res = await fetch('/api/ping');
  if (!res.ok) throw new Error(`ping failed: ${res.status}`);
  return res.text();
}

export function App() {
  const { data, error } = useQuery({ queryKey: ['ping'], queryFn: ping });
  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header px="md" style={{ display: 'flex', alignItems: 'center' }}>
        <Brand />
      </AppShell.Header>
      <AppShell.Main>
        <Text>API: {error ? 'unavailable' : (data ?? '…')}</Text>
      </AppShell.Main>
    </AppShell>
  );
}
