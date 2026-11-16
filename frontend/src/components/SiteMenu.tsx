import { Burger, Group, Menu } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { NavLink, useLocation } from 'react-router-dom';

export const SITE_LINKS = [
  { to: '/', label: 'Search' },
  { to: '/journals', label: 'Journals' },
  { to: '/about', label: 'About' },
];

const isActive = (to: string, path: string) => (to === '/' ? path === '/' : path === to || path.startsWith(`${to}/`));

/** The three site links: inline from the sm breakpoint up, a burger menu below. */
export function SiteMenu() {
  const [opened, { toggle, close }] = useDisclosure(false);
  const { pathname } = useLocation();
  return (
    <>
      <Group component="nav" aria-label="Site" gap="md" ml="xl" visibleFrom="sm" wrap="nowrap">
        {SITE_LINKS.map((l) => (
          <NavLink key={l.to} to={l.to} end={l.to === '/'}
            style={({ isActive: a }) => ({ textDecoration: 'none', color: 'inherit', fontWeight: a ? 700 : 400,
              borderBottom: a ? '2px solid var(--mantine-color-jay-6)' : '2px solid transparent' })}>
            {l.label}
          </NavLink>
        ))}
      </Group>
      <Menu opened={opened} onChange={(o) => (o ? toggle() : close())} position="bottom-start">
        <Menu.Target>
          <Burger opened={opened} onClick={toggle} size="sm" ml="sm" hiddenFrom="sm" aria-label="Menu" />
        </Menu.Target>
        <Menu.Dropdown>
          {SITE_LINKS.map((l) => (
            <Menu.Item key={l.to} component={NavLink} to={l.to} onClick={close}
              data-active={isActive(l.to, pathname) || undefined}
              fw={isActive(l.to, pathname) ? 700 : 400}>
              {l.label}
            </Menu.Item>
          ))}
        </Menu.Dropdown>
      </Menu>
    </>
  );
}
