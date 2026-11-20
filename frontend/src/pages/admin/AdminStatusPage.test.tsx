import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { AdminStatusPage } from './AdminStatusPage';
import { renderApp } from '../../test/render';
import { server } from '../../test/server';

describe('AdminStatusPage', () => {
  it('rendersTaskCounts', async () => {
    server.use(http.get('/api/admin/status', () => HttpResponse.json({
      tasks: { FIND_NAMES: { QUEUED: 17, DONE: 4 } }, works: { INDEXED: { FULL: 3 } }, sources: { CORE: 5 },
      apiUsage: [{ service: 'openalex', calls: 12 }], harvestRuns: [], xrReleases: [{ key: 1, alias: 'XR' }],
      backfillRuns: [], finderRollouts: [],
    })));
    renderApp(<AdminStatusPage />, '/admin/status');
    expect(await screen.findByText('FIND_NAMES')).toBeInTheDocument();
    expect(screen.getByText('17')).toBeInTheDocument();
    expect(screen.getByText('openalex')).toBeInTheDocument();
  });
});
