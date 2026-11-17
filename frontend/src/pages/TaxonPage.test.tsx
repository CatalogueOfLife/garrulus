import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { TaxonPage, relevantVerbatim } from './TaxonPage';
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
        return HttpResponse.json({ ...emptyPage, total: 12, results: [work(1, 'Carabid revision'), work(3, 'Second paper')] });
      }),
      http.get('/api/works/1', () => HttpResponse.json({ id: 1, authors: [], retained: false, hasActs: false, codes: [], taxgroups: [], names: [],
        contexts: [{ localId: 'c', verbatim: 'Coleoptera: Carabidae', ranks: [{ rank: 'ORDER', name: 'Coleoptera' }, { rank: 'FAMILY', name: 'Carabidae' }], scope: 'DOCUMENT', blockId: 'b' }], text: null })),
      http.get('/api/works/3', () => HttpResponse.json({ id: 3, authors: [], retained: false, hasActs: false, codes: [], taxgroups: [], names: [],
        contexts: [{ localId: 'c', verbatim: 'Coleoptera: Carabidae', ranks: [{ rank: 'ORDER', name: 'Coleoptera' }, { rank: 'FAMILY', name: 'Carabidae' }], scope: 'DOCUMENT', blockId: 'b' },
                   { localId: 'd', verbatim: 'Insecta; Coleoptera', ranks: [{ rank: 'CLASS', name: 'Insecta' }, { rank: 'ORDER', name: 'Coleoptera' }], scope: 'DOCUMENT', blockId: 'b' },
                   { localId: 'e', verbatim: 'Genus Carabus', ranks: [{ rank: 'GENUS', name: 'Carabus' }], scope: 'SECTION', blockId: 'b' }], text: null })),
    );
    renderApp(<Routes><Route path="/taxon/:id" element={<TaxonPage />} /></Routes>, '/taxon/T1');
    expect(await screen.findByRole('heading', { name: 'Carabus' })).toBeInTheDocument();
    expect(screen.getByText('genus')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 }).querySelector('i')).not.toBeNull();
    expect(screen.getByRole('link', { name: 'Atom feed' })).toHaveAttribute('href', '/feeds/taxon/T1.atom');
    expect(await screen.findByText('6')).toBeInTheDocument();
    expect(await screen.findByText('Carabidae')).toBeInTheDocument();
    expect(await screen.findAllByTestId('year-bar')).toHaveLength(2);
    const recent = screen.getByRole('region', { name: 'Recent papers (main subject)' });
    expect(await within(recent).findByText('Carabid revision')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /All 12 papers/ })).toHaveAttribute('href', '/?taxon=T1&mainSubjectOnly=true');
    const names = screen.getByRole('region', { name: 'New names (main subject)' });
    expect(await within(names).findByText('A new species of Carabus')).toBeInTheDocument();
    const verb = screen.getByRole('region', { name: 'Verbatim classifications used' });
    // kept: a context naming the taxon itself or one of its ancestors (Animalia, Carabidae)
    expect(await within(verb).findByText('Genus Carabus')).toBeInTheDocument();
    expect(within(verb).getAllByText('Coleoptera: Carabidae')).toHaveLength(1); // de-duplicated
    // dropped: Insecta and Coleoptera are not in the CLB classification of this taxon
    expect(within(verb).queryByText('Insecta; Coleoptera')).not.toBeInTheDocument();
    expect(document.title).toBe('Garrulus — Carabus');
  });

  it('relevantVerbatimKeepsContextsNamingTheTaxonOrAnAncestor', () => {
    const ctx = (verbatim: string, ...names: string[]) => ({
      localId: verbatim, verbatim, ranks: names.map((name) => ({ rank: 'FAMILY', name })), scope: 'DOCUMENT', blockId: 'b',
    });
    const contexts = [ctx('A: B', 'A', 'B'), ctx('C', 'C'), ctx('a: b', 'carabidae '), ctx('A: B', 'A', 'B'), ctx('none')];
    expect(relevantVerbatim(contexts, ['Carabidae', 'B', undefined])).toEqual(['A: B', 'a: b']);
    expect(relevantVerbatim(contexts, [undefined])).toEqual([]);
  });
});
