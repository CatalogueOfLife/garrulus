import { screen } from '@testing-library/react';
import { PageHeader } from './PageHeader';
import { Footer } from './Footer';
import { signedOut, server } from '../test/server';
import { renderApp } from '../test/render';

describe('PageHeader', () => {
  beforeEach(() => server.use(signedOut));
  it('bigShowsHeadingTaglineMenuIntroAndSearchSlot', () => {
    renderApp(<PageHeader variant="big" searchSlot={<input aria-label="slot" />} intro="Garrulus reads journals." />, '/');
    expect(screen.getByRole('heading', { name: 'Garrulus' })).toBeInTheDocument();
    const tagline = screen.getByText('Names in the literature of biodiversity science');
    // the site menu sits in the tagline's row, as in the compact header, not below the wordmark
    expect(tagline.parentElement).toContainElement(screen.getByRole('navigation', { name: 'Site' }));
    expect(screen.getByText('Garrulus reads journals.').parentElement).toContainElement(screen.getByLabelText('slot'));
  });
  it('compactHasWordmarkLinkAndNoTagline', () => {
    renderApp(<PageHeader variant="compact" />, '/about');
    expect(screen.getByRole('link', { name: /Garrulus/ })).toHaveAttribute('href', '/');
    expect(screen.queryByText('Names in the literature of biodiversity science')).toBeNull();
  });
  it('currentNavLinkIsMarked', () => {
    renderApp(<PageHeader variant="compact" />, '/journals');
    expect(screen.getByRole('link', { name: 'Journals' })).toHaveAttribute('aria-current', 'page');
  });
});

describe('Footer', () => {
  it('linksToColAboutAndApi', () => {
    renderApp(<Footer />);
    expect(screen.getByRole('link', { name: /Catalogue of Life/ })).toHaveAttribute('href', 'https://www.catalogueoflife.org');
    expect(screen.getByRole('link', { name: 'About' })).toHaveAttribute('href', '/about');
    expect(screen.getByRole('link', { name: 'API' })).toHaveAttribute('href', '/api/openapi.yaml');
  });
});
