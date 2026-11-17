import { screen } from '@testing-library/react';
import { PageHeader } from './PageHeader';
import { Footer } from './Footer';
import { signedOut, server } from '../test/server';
import { renderApp } from '../test/render';

describe('PageHeader', () => {
  beforeEach(() => server.use(signedOut));
  it('bigShowsHeadingTaglineAndSearchSlot', () => {
    renderApp(<PageHeader variant="big" searchSlot={<input aria-label="slot" />} />, '/');
    expect(screen.getByRole('heading', { name: 'Garrulus' })).toBeInTheDocument();
    expect(screen.getByText('Names in the literature of biodiversity science')).toBeInTheDocument();
    expect(screen.getByLabelText('slot')).toBeInTheDocument();
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
