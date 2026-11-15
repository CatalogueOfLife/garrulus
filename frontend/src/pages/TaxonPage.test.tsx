import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { TaxonPage } from './TaxonPage';
import { server, work, emptyPage, xrHandler } from '../test/server';
import { renderApp } from '../test/render';

describe('TaxonPage', () => {
  it('taxonPageShowsYearsRecentWorksAndNewNames', async () => {
    const seen: string[] = [];
    server.use(
      xrHandler,
      http.get('https://api.checklistbank.org/dataset/316691/taxon/T1', () => HttpResponse.json({
        id: 'T1', label: 'Carabus Linnaeus, 1758', status: 'accepted', parentId: 'F', name: { scientificName: 'Carabus', rank: 'genus' },
      })),
      http.get('https://api.checklistbank.org/dataset/316691/taxon/T1/classification', () => HttpResponse.json([
        { id: 'A', name: 'Animalia', rank: 'kingdom' }, { id: 'F', name: 'Carabidae', rank: 'family' },
      ])),
      http.get('/api/taxa/T1/stats', () => HttpResponse.json({ taxonId: 'T1', total: 6, mainSubjectTotal: 3, years: [
        { year: 2025, works: 2, mainSubjectWorks: 1 }, { year: 2026, works: 4, mainSubjectWorks: 2 }] })),
      http.get('/api/works', ({ request }) => {
        const q = new URL(request.url).searchParams;
        seen.push(q.toString());
        expect(q.get('taxon')).toBe('T1');
        if (q.get('hasActs') === 'true') return HttpResponse.json({ ...emptyPage, total: 1, results: [{ ...work(2, 'A new species of Carabus'), hasActs: true }] });
        return HttpResponse.json({ ...emptyPage, total: 2, results: [work(1, 'Carabid revision'), work(3, 'Second paper')] });
      }),
      http.get('/api/works/1', () => HttpResponse.json({ id: 1, authors: [], retained: false, hasActs: false, codes: [], taxgroups: [], names: [],
        contexts: [{ localId: 'c', verbatim: 'Coleoptera: Carabidae', ranks: {}, scope: 'DOCUMENT', blockId: 'b' }], text: null })),
      http.get('/api/works/3', () => HttpResponse.json({ id: 3, authors: [], retained: false, hasActs: false, codes: [], taxgroups: [], names: [],
        contexts: [{ localId: 'c', verbatim: 'Coleoptera: Carabidae', ranks: {}, scope: 'DOCUMENT', blockId: 'b' },
                   { localId: 'd', verbatim: 'Insecta; Coleoptera', ranks: {}, scope: 'DOCUMENT', blockId: 'b' }], text: null })),
    );
    renderApp(<Routes><Route path="/taxon/:id" element={<TaxonPage />} /></Routes>, '/taxon/T1');
    expect(await screen.findByRole('heading', { name: 'Carabus' })).toBeInTheDocument();
    expect(screen.getByText('genus')).toBeInTheDocument();
    expect(await screen.findByText('Carabidae')).toBeInTheDocument();
    expect(await screen.findAllByTestId('year-bar')).toHaveLength(2);
    const recent = screen.getByRole('region', { name: 'Recent papers' });
    expect(await within(recent).findByText('Carabid revision')).toBeInTheDocument();
    const names = screen.getByRole('region', { name: 'New names' });
    expect(await within(names).findByText('A new species of Carabus')).toBeInTheDocument();
    const verb = screen.getByRole('region', { name: 'Verbatim classifications used' });
    expect(await within(verb).findByText('Insecta; Coleoptera')).toBeInTheDocument();
    expect(within(verb).getAllByText('Coleoptera: Carabidae')).toHaveLength(1); // de-duplicated
    expect(document.title).toBe('Garrulus — Carabus');
  });
});
