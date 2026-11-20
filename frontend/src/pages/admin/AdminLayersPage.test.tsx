import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { AdminLayersPage } from './AdminLayersPage';
import { renderApp } from '../../test/render';
import { server } from '../../test/server';

describe('AdminLayersPage', () => {
  it('gridShowsStatesAsText', async () => {
    server.use(http.get('/api/admin/layers', () => HttpResponse.json({
      health: [
        { layer: 'OAI', day: '2026-11-19', state: 'FAILING', failedRuns: 3, yield: 0 },
        { layer: 'OAI', day: '2026-11-20', state: 'OK', failedRuns: 0, yield: 9 },
        { layer: 'CROSSREF', day: '2026-11-20', state: 'DEGRADED', failedRuns: 0, yield: 1 },
      ],
      contribution: { OAI: { first: 4, only: 2 } },
      contributionByCountry: { BR: { OAI: { first: 3, only: 1 } } },
      coverage: [{ sourceId: 5, title: 'Revista X', country: 'BR', layer: 'OAI', sightings28d: 12, misses: 3 }],
    })));
    renderApp(<AdminLayersPage />, '/admin/layers');
    expect(await screen.findByText('OAI 2026-11-19 FAILING')).toBeInTheDocument();
    expect(screen.getByText('OAI 2026-11-20 OK')).toBeInTheDocument();
    expect(screen.getByText('CROSSREF 2026-11-20 DEGRADED')).toBeInTheDocument();
    expect(screen.getByText('Revista X')).toBeInTheDocument();
  });
});
