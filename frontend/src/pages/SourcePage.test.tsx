import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { SourcePage } from './SourcePage';
import { server, work, taxGroupHandler } from '../test/server';
import { renderApp } from '../test/render';

function renderSource() {
  renderApp(<Routes><Route path="/source/:id" element={<SourcePage />} /></Routes>, '/source/7');
}

describe('SourcePage', () => {
  it('sourcePageShowsCategorisationAndRecentWorks', async () => {
    server.use(
      taxGroupHandler,
      http.get('/api/sources/7', () => HttpResponse.json({
        id: 7, title: 'Zootaxa', publisher: 'Magnolia Press', tier: 'CORE', journalType: 'TAXONOMIC',
        primarySubfield: '1109', subfields: ['1109', '1105', '4242'],
        taxgroups: ['Insects', 'Animals'], taxgroupShares: { Animals: 0.12, Insects: 0.62 },
        codes: ['ZOOLOGICAL'], oaModel: 'HYBRID', country: 'NZ', nameDensity: 4.2371, actRate: 0.31, worksPerYear: 1500,
        years: [{ year: 2023, works: 1400 }, { year: 2024, works: 1500 }],
        recentWorks: [work(1, 'A recent paper')],
      })),
    );
    renderSource();
    expect(await screen.findByRole('heading', { name: 'Zootaxa' })).toBeInTheDocument();
    expect(screen.getByText(/Magnolia Press/)).toBeInTheDocument();
    // no all-caps labels: API values in sentence case
    expect(screen.getByText('Core')).toBeInTheDocument();
    const groups = screen.getByRole('region', { name: 'Taxonomic groups' });
    expect(await within(groups).findByText('62%')).toBeInTheDocument();
    expect(within(groups).getByText('12%')).toBeInTheDocument();
    // the icon is decoration next to the visible name
    await waitFor(() => expect(groups.querySelector('img[src="https://icons.example/insects.svg"]')).toHaveAttribute('alt', ''));
    expect(within(groups).queryByRole('img')).toBeNull();
    const cat = screen.getByRole('region', { name: 'Categorisation' });
    expect(within(cat).getByText('Zoological')).toBeInTheDocument();
    expect(within(cat).getByText('Taxonomic')).toBeInTheDocument();
    expect(within(cat).getByText('4.2')).toBeInTheDocument();
    const insects = within(cat).getByText('Insect Science');
    expect(insects).toHaveAttribute('title', '1109');
    expect(insects).toHaveAttribute('data-primary', 'true');
    const eco = within(cat).getByText('Ecology, Evolution, Behavior and Systematics');
    expect(eco).not.toHaveAttribute('data-primary');
    expect(within(cat).getByText('4242')).toBeInTheDocument();
    expect(within(cat).getByText('Hybrid')).toBeInTheDocument();
    expect(within(cat).getByText('NZ')).toBeInTheDocument();
    const perYear = screen.getByRole('region', { name: 'Papers per year' });
    expect(within(perYear).getByText('2024')).toBeInTheDocument();
    const recent = screen.getByRole('region', { name: 'Recent papers' });
    expect(within(recent).getByText('A recent paper')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /All papers/ })).toHaveAttribute('href', '/?source=7');
    expect(document.title).toBe('Garrulus — Zootaxa');
  });

  it('offers follow and feed only for sources the feeds serve', async () => {
    const detail = (tier: string) => ({ id: 7, title: 'Zootaxa', tier, subfields: [], taxgroups: [], codes: [], years: [], recentWorks: [] });
    server.use(taxGroupHandler, http.get('/api/sources/7', () => HttpResponse.json(detail('CORE'))));
    const core = renderApp(<Routes><Route path="/source/:id" element={<SourcePage />} /></Routes>, '/source/7');
    expect(await screen.findByRole('heading', { name: 'Zootaxa' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Follow' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Atom feed' })).toHaveAttribute('href', '/feeds/source/7.atom');
    core.unmount();

    server.use(http.get('/api/sources/7', () => HttpResponse.json(detail('CANDIDATE'))));
    renderSource();
    expect(await screen.findByRole('heading', { name: 'Zootaxa' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Follow' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Follow/ })).toBeNull();
    expect(document.querySelector('a[href="/feeds/source/7.atom"]')).toBeNull();
  });

  it('unknownSourceIsFriendly', async () => {
    server.use(taxGroupHandler, http.get('/api/sources/7', () => HttpResponse.json({ error: 'nope' }, { status: 404 })));
    renderSource();
    expect(await screen.findByText(/journal was not found/i)).toBeInTheDocument();
  });
});
