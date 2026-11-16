import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Brand } from './Brand';
import { SiteMenu } from './SiteMenu';
import { renderApp } from '../test/render';

describe('SiteMenu and Brand', () => {
  it('brandLinksHome', () => {
    renderApp(<Brand />, '/about');
    expect(screen.getByRole('link', { name: 'Garrulus home' })).toHaveAttribute('href', '/');
  });
  it('showsTheThreeLinksAndHighlightsTheActiveOne', () => {
    renderApp(<SiteMenu />, '/journals');
    const nav = screen.getByRole('navigation', { name: 'Site' });
    expect(nav.querySelector('a[href="/"]')).toHaveTextContent('Search');
    expect(nav.querySelector('a[href="/about"]')).toHaveTextContent('About');
    expect(screen.getByRole('link', { name: 'Journals' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'About' })).not.toHaveAttribute('aria-current');
  });
  it('burgerOpensTheSameLinks', async () => {
    renderApp(<SiteMenu />, '/');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Menu' }));
    expect(await screen.findAllByRole('menuitem')).toHaveLength(3);
  });
});
