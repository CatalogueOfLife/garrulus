import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { AdminJournalsPage } from './AdminJournalsPage';
import { renderApp } from '../../test/render';
import { server } from '../../test/server';

const item = (id: number, title: string, over = {}) => ({
  id, title, tier: 'CANDIDATE', active: false, issns: [], origin: 'DOAJ', country: 'BR', candidateScore: 0.5,
  publicationStatus: 'ACTIVE', dormant: false, revival: false, signalOrigins: ['DOAJ'], routes: { OAI: 1 }, registrations: 0,
  sample: { sampledAt: '2026-11-01T00:00:00Z', articles: 10, withAbstract: 9, namesPerArticle: 4.2, actShare: 0.1, taxgroups: { Insects: 7 } },
  ...over,
});

describe('AdminJournalsPage', () => {
  it('listsCandidatesByScore', async () => {
    const seen: URLSearchParams[] = [];
    server.use(http.get('/api/admin/sources', ({ request }) => {
      seen.push(new URL(request.url).searchParams);
      return HttpResponse.json({ total: 2, items: [item(1, 'Revista A', { candidateScore: 0.9, proposal: 'PROMOTE' }), item(2, 'Revista B', { dormant: true })] });
    }));
    renderApp(<AdminJournalsPage />, '/admin/journals');
    expect(await screen.findByRole('link', { name: 'Revista A' })).toHaveAttribute('href', '/admin/journals/1');
    expect(seen[0].get('tier')).toBe('CANDIDATE');
    expect(seen[0].get('sort')).toBe('score');
    expect(screen.getByText('promote proposal')).toBeInTheDocument();
    expect(screen.getByText('dormant', { selector: 'span' })).toBeInTheDocument();
    expect(screen.getAllByText(/4\.2 names\/article/)).toHaveLength(2);
    expect(screen.getAllByText('OAI:1')).toHaveLength(2);
  });
  it('filtersByOrigin', async () => {
    const seen: string[] = [];
    server.use(http.get('/api/admin/sources', ({ request }) => {
      seen.push(new URL(request.url).search);
      return HttpResponse.json({ total: 0, items: [] });
    }));
    renderApp(<AdminJournalsPage />, '/admin/journals');
    await waitFor(() => expect(seen).toHaveLength(1));
    await userEvent.setup().selectOptions(screen.getByLabelText('Origin'), 'PLAZI');
    await waitFor(() => expect(seen.at(-1)).toContain('origin=PLAZI'));
  });
  it('showsNotAdminOn403', async () => {
    server.use(http.get('/api/admin/sources', () => new HttpResponse(null, { status: 403 })));
    renderApp(<AdminJournalsPage />, '/admin/journals');
    expect(await screen.findByText('You are not an admin.')).toBeInTheDocument();
  });
});
