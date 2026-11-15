import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { WorkPage } from './WorkPage';
import { server } from '../test/server';
import { renderApp } from '../test/render';
import type { NameDetail, WorkDetail } from '../api';

const nm = (id: number, name: string, over: Partial<NameDetail> = {}): NameDetail => ({
  id, name, kind: 'SCIENTIFIC', placementMethod: 'MATCH', usageId: null, usageName: null, salience: 0.5,
  mainSubject: false, onlyReference: false, mentionCount: 1, actTypes: [], bestSnippet: '', classification: [], ...over,
});

const base: WorkDetail = {
  id: 5, title: 'Carabid notes', year: 2026, authors: [{ name: 'A. Author' }], retained: false, hasActs: false,
  codes: [], taxgroups: [], contexts: [], text: null,
  names: [
    nm(1, 'Carabus auratus', { usageId: 'T1', usageName: 'Carabus auratus L.', classification: [{ id: 'F', name: 'Carabidae', rank: 'family' }, { id: 'G', name: 'Carabus', rank: 'genus' }], bestSnippet: 'SNIPPET-ONE' }),
    nm(2, 'Carabus novus', { placementMethod: 'GENUS', usageId: 'G', usageName: 'Carabus' }),
    nm(3, 'Foo bar', { placementMethod: 'VERBATIM_CONTEXT', usageId: 'T9', usageName: 'Foidae' }),
    nm(4, 'Lost name', { placementMethod: 'NONE' }),
  ],
};

function mount(w: WorkDetail) {
  server.use(http.get('/api/works/5', () => HttpResponse.json(w)));
  renderApp(<Routes><Route path="/work/:id" element={<WorkPage />} /></Routes>, '/work/5');
}

describe('WorkPage', () => {
  it('workPageGroupsNamesByPlacement', async () => {
    mount(base);
    const col = await screen.findByRole('region', { name: 'Placed in COL' });
    expect(within(col).getByRole('link', { name: 'Carabus auratus' })).toHaveAttribute('href', '/name/1');
    expect(within(col).getByRole('link', { name: 'Carabus novus' })).toHaveAttribute('href', '/name/2');
    expect(within(col).getByRole('link', { name: 'Carabus auratus' }).closest('li')!
      .querySelector('a[href="/taxon/T1"]')).not.toBeNull();
    const verb = screen.getByRole('region', { name: 'Placed by the verbatim classification' });
    expect(within(verb).getByRole('link', { name: 'Foo bar' })).toHaveAttribute('href', '/name/3');
    expect(within(verb).getByRole('link', { name: 'Foidae' })).toHaveAttribute('href', '/taxon/T9');
    const none = screen.getByRole('region', { name: 'Not placed' });
    expect(within(none).getByRole('link', { name: 'Lost name' })).toHaveAttribute('href', '/name/4');
    expect(document.title).toBe('Garrulus — Carabid notes');
  });

  it('verbatimClassificationNextToColOne', async () => {
    mount({ ...base, contexts: [{ localId: 'c1', verbatim: 'Coleoptera: Carabidae: Carabus', ranks: [{ rank: 'ORDER', name: 'Coleoptera' }], scope: 'DOCUMENT', blockId: 'b1' }] });
    const verbatim = await screen.findByRole('region', { name: 'Verbatim classification in the article' });
    expect(within(verbatim).getByText('Coleoptera: Carabidae: Carabus')).toBeInTheDocument();
    const col = screen.getByRole('region', { name: 'Placed in COL' });
    expect(within(col).getByText(/Carabidae › Carabus/)).toBeInTheDocument();
  });

  it('closedWorkShowsNoTextOnlySnippets', async () => {
    mount(base);
    expect(await screen.findByText(/SNIPPET-ONE/)).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Full text' })).toBeNull();
  });

  it('openWorkShowsHighlightedText', async () => {
    mount({ ...base, retained: true, licence: 'CC-BY-4.0', text: [{
      id: 'b1', type: 'paragraph', level: 0, sectionPath: [], text: '😀 Carabus auratus.',
      mentions: [{ id: 1, nameId: 1, startCp: 2, endCp: 17, verbatim: 'Carabus auratus' }],
    }] });
    const text = await screen.findByRole('region', { name: 'Full text' });
    expect(within(text).getByRole('link', { name: 'Carabus auratus' })).toHaveAttribute('href', '/name/1');
    expect(screen.queryByText(/SNIPPET-ONE/)).toBeNull();
  });
});
