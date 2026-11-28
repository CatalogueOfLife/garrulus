import { screen } from '@testing-library/react';
import { AboutPage } from './AboutPage';
import { renderApp } from '../test/render';
import { infoHandler, server, xrHandler } from '../test/server';

describe('AboutPage', () => {
  it('explainsNameAndTaxonPagesAndShowsTheRelease', async () => {
    server.use(xrHandler, infoHandler);
    renderApp(<AboutPage />, '/about');
    expect(screen.getByRole('heading', { name: 'About Garrulus' })).toBeInTheDocument();
    expect(screen.getByText(/is about one name as it is written in papers/)).toBeInTheDocument();
    expect(screen.getByText(/gathers the\s+papers about this taxon and everything below it/)).toBeInTheDocument();
    expect(screen.getByText(/Full text is kept and shown only for open-licensed papers/)).toBeInTheDocument();
    expect((await screen.findByText('2000')).closest('li')).toHaveTextContent('Papers published from 2000 on are included');
    expect(await screen.findByText('COL26.10 XR')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'API description' })).toHaveAttribute('href', '/api/openapi.yaml');
    expect(screen.getByRole('link', { name: 'Source code' })).toHaveAttribute('href', 'https://github.com/CatalogueOfLife/garrulus');
  });
});
