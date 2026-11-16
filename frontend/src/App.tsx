import { AppShell } from '@mantine/core';
import { useTimeZoneProposal } from './auth/useTimeZoneProposal';
import { AccountMenu } from './components/AccountMenu';
import { Brand } from './components/Brand';
import { AppRoutes } from './routes';

export function App() {
  useTimeZoneProposal();
  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header px="md" style={{ display: 'flex', alignItems: 'center' }}>
        <Brand />
        <AccountMenu />
      </AppShell.Header>
      <AppShell.Main>
        <AppRoutes />
      </AppShell.Main>
    </AppShell>
  );
}
