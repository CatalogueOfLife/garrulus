import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { NamePage } from './NamePage';
import { server, work, emptyPage } from '../test/server';
import { renderApp } from '../test/render';

const worksHandler = http.get('/api/works', ({ request }) => {
  expect(new URL(request.url).searchParams.get('name')).toBe('5');
  return HttpResponse.json({ ...emptyPage, total: 1, results: [work(1, 'Carabid revision')] });
});

function renderName(id: string) {
  renderApp(<Routes><Route path="/name/:id" element={<NamePage />} /></Routes>, `/name/${id}`);
}

describe('NamePage', () => {
  it('namePageShowsMatchTimelineAndFirstAppearance', async () => {
    server.use(
      http.get('/api/names/5', () => HttpResponse.json({
        id: 5, name: 'Carabus nemoralis', kind: 'BINOMIAL', rank: 'SPECIES',
        match: { usageId: 'S1', usageName: 'Carabus nemoralis', rank: 'species', status: 'synonym', acceptedId: 'S2',
          classification: [{ id: 'A', name: 'Animalia', rank: 'kingdom' }, { id: 'F', name: 'Carabidae', rank: 'family' }] },
        timeline: [{ year: 2024, works: 1 }, { year: 2026, works: 3 }],
        firstAppearance: { workId: 9, title: 'The first paper', publicationDate: '2024-03-01' },
      })),
      worksHandler,
    );
    renderName('5');
    expect(await screen.findByRole('heading', { name: 'Carabus nemoralis' })).toBeInTheDocument();
    // rank and kind come in upper case from the API; no all-caps labels
    expect(screen.getByText('species')).toBeInTheDocument();
    expect(screen.getByText('binomial')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Atom feed' })).toHaveAttribute('href', '/feeds/name/5.atom');
    expect(screen.getByRole('heading', { level: 1 }).querySelector('i')).not.toBeNull();
    const match = screen.getByRole('region', { name: 'Match in COL' });
    expect(within(match).getByRole('link', { name: 'Carabus nemoralis' })).toHaveAttribute('href', '/taxon/S1');
    expect(within(match).getByRole('link', { name: /accepted/i })).toHaveAttribute('href', '/taxon/S2');
    expect(within(match).getByRole('link', { name: 'Carabidae' })).toHaveAttribute('href', '/taxon/F');
    expect(await screen.findAllByTestId('year-bar')).toHaveLength(3); // 2025 gap filled
    const first = screen.getByRole('region', { name: 'First appearance' });
    expect(within(first).getByRole('link', { name: 'The first paper' })).toHaveAttribute('href', '/work/9');
    expect(within(first).getByText(/1 Mar 2024/)).toBeInTheDocument();
    const works = screen.getByRole('region', { name: 'Papers naming this name' });
    expect(await within(works).findByText('Carabid revision')).toBeInTheDocument();
    expect(document.title).toBe('Garrulus — Carabus nemoralis');
    // a link inside a sentence is underlined, not only on hover
    expect(screen.getByRole('link', { name: 'What is this?' })).toHaveAttribute('data-underline', 'always');
  });

  it('unmatchedNameSaysSo', async () => {
    server.use(
      http.get('/api/names/5', () => HttpResponse.json({ id: 5, name: 'Foo bar', kind: 'SCIENTIFIC', match: null, timeline: [], firstAppearance: null })),
      worksHandler,
    );
    renderName('5');
    expect(await screen.findByText(/not matched to the Catalogue of Life/i)).toBeInTheDocument();
  });

  it('unknownNameIsFriendly', async () => {
    server.use(http.get('/api/names/5', () => HttpResponse.json({ error: 'nope' }, { status: 404 })), worksHandler);
    renderName('5');
    expect(await screen.findByText(/name was not found/i)).toBeInTheDocument();
  });
});
