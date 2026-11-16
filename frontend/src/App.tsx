import { AppShell } from '@mantine/core';
import { useTimeZoneProposal } from './auth/useTimeZoneProposal';
import { AccountMenu } from './components/AccountMenu';
import { Brand } from './components/Brand';
import { SiteMenu } from './components/SiteMenu';
import { AppRoutes } from './routes';

export function App() {
  useTimeZoneProposal();
  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header px="md" style={{ display: 'flex', alignItems: 'center' }}>
        <Brand />
        <SiteMenu />
        <AccountMenu />
      </AppShell.Header>
      <AppShell.Main>
        <AppRoutes />
      </AppShell.Main>
    </AppShell>
  );
}
