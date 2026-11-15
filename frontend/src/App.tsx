import { AppShell } from '@mantine/core';
import { Brand } from './components/Brand';
import { AppRoutes } from './routes';

export function App() {
  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header px="md" style={{ display: 'flex', alignItems: 'center' }}>
        <Brand />
      </AppShell.Header>
      <AppShell.Main>
        <AppRoutes />
      </AppShell.Main>
    </AppShell>
  );
}
