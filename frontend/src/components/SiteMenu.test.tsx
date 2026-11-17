import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SiteMenu } from './SiteMenu';
import { renderApp } from '../test/render';

describe('SiteMenu', () => {
  it('showsTheThreeLinksAndHighlightsTheActiveOne', () => {
    renderApp(<SiteMenu />, '/journals');
    const nav = screen.getByRole('navigation', { name: 'Site' });
    expect(nav.querySelector('a[href="/"]')).toHaveTextContent('Search');
    expect(nav.querySelector('a[href="/about"]')).toHaveTextContent('About');
    expect(screen.getByRole('link', { name: 'Journals' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'About' })).not.toHaveAttribute('aria-current');
  });
  it('keepsTheRedUnderlineOnTheCurrentLinkAfterInAppNavigation', async () => {
    renderApp(<SiteMenu />, '/journals');
    await userEvent.setup().click(screen.getByRole('link', { name: 'About' }));
    const about = screen.getByRole('link', { name: 'About' });
    expect(about).toHaveAttribute('aria-current', 'page');
    expect(about.style.textDecorationLine).toBe('underline');
    expect(about.style.textDecorationColor).toBe('var(--gr-red)');
  });
  it('burgerOpensTheSameLinks', async () => {
    renderApp(<SiteMenu />, '/');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Menu' }));
    expect(await screen.findAllByRole('menuitem')).toHaveLength(3);
  });
});
