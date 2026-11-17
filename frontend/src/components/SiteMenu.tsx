import { Burger, Group, Menu } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { NavLink, useLocation } from 'react-router-dom';

export const SITE_LINKS = [
  { to: '/', label: 'Search' },
  { to: '/journals', label: 'Journals' },
  { to: '/about', label: 'About' },
];

// the current place: underlined in red, as in the inline nav (the font ships no bold)
const current = { textDecorationLine: 'underline', textDecorationColor: 'var(--gr-red)', textDecorationThickness: '2px', textUnderlineOffset: '4px' };

const isActive = (to: string, path: string) => (to === '/' ? path === '/' : path === to || path.startsWith(`${to}/`));

/** The three site links: inline from the sm breakpoint up, a burger menu below. */
export function SiteMenu() {
  const [opened, { toggle, close }] = useDisclosure(false);
  const { pathname } = useLocation();
  return (
    <>
      <Group component="nav" aria-label="Site" gap={28} visibleFrom="sm" wrap="nowrap">
        {SITE_LINKS.map((l) => (
          <NavLink key={l.to} to={l.to} end={l.to === '/'}
            style={({ isActive: a }) => ({ color: 'inherit',
              // longhands only: React drops the colour and thickness when a shorthand changes on navigation
              textDecorationLine: a ? 'underline' : 'none', textDecorationColor: 'var(--gr-red)',
              textDecorationThickness: '2px', textUnderlineOffset: '6px' })}>
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
              style={isActive(l.to, pathname) ? current : undefined}>
              {l.label}
            </Menu.Item>
          ))}
        </Menu.Dropdown>
      </Menu>
    </>
  );
}
