import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { AdminJournalPage } from './AdminJournalPage';
import { renderApp } from '../../test/render';
import { server } from '../../test/server';

const detail = (over = {}) => ({
  source: { id: 5, title: 'Revista <b>X</b>', tier: 'CANDIDATE', active: false, issns: ['1234-5678'], origin: 'DOAJ', country: 'BR',
    publisher: 'Pub', candidateScore: 0.5, publicationStatus: 'ACTIVE', dormant: false, revival: false, signalOrigins: [], routes: {}, registrations: 0 },
  signals: [{ origin: 'DOAJ', firstSeen: '2026-01-01T00:00:00Z', lastSeen: '2026-02-01T00:00:00Z', detail: { url: 'javascript:alert(1)' } }],
  samples: [], routes: [], registrations: [], disciplines: ['1103'], disciplineSetBy: 'DERIVED', ...over,
});
const page = () => renderApp(<Routes><Route path="/admin/journals/:id" element={<AdminJournalPage />} /></Routes>, '/admin/journals/5');
const useDetail = (d = detail()) => server.use(http.get('/api/admin/sources/5', () => HttpResponse.json(d)));

describe('AdminJournalPage', () => {
  it('acceptSendsTierPatchWithXsrf', async () => {
    document.cookie = 'XSRF-TOKEN=tok123';
    useDetail();
    let body: unknown; let xsrf: string | null = null;
    server.use(http.patch('/api/admin/sources/5', async ({ request }) => {
      body = await request.json(); xsrf = request.headers.get('X-XSRF-TOKEN');
      return HttpResponse.json(detail().source);
    }));
    page();
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Accept as CORE' }));
    await waitFor(() => expect(body).toEqual({ tier: 'CORE', active: true }));
    expect(xsrf).toBe('tok123');
  });
  it('rendersHarvestedTextAsText', async () => {
    useDetail();
    page();
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Revista <b>X</b>');
    expect(document.querySelector('h1 b')).toBeNull();
    expect(screen.queryByRole('link', { name: 'open' })).toBeNull();
    expect(screen.getByText(/javascript:alert\(1\)/)).toBeInTheDocument();
  });
  it('declineAsksFirst', async () => {
    useDetail();
    let calls = 0; let body: unknown;
    server.use(http.patch('/api/admin/sources/5', async ({ request }) => { calls++; body = await request.json(); return HttpResponse.json(detail().source); }));
    page();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Decline' }));
    expect(calls).toBe(0);
    await user.click(screen.getByRole('button', { name: 'Yes, decline' }));
    await waitFor(() => expect(body).toEqual({ tier: 'EXCLUDED' }));
  });
  it('ceaseFormRequiresReason', async () => {
    useDetail();
    let body: unknown;
    server.use(http.patch('/api/admin/sources/5', async ({ request }) => { body = await request.json(); return HttpResponse.json(detail().source); }));
    page();
    const user = userEvent.setup();
    const cease = await screen.findByRole('button', { name: 'Cease' });
    expect(cease).toBeDisabled();
    await user.selectOptions(screen.getByLabelText(/Reason/), 'Merged into another journal');
    await user.type(screen.getByLabelText('Last issue year'), '2019');
    await user.click(screen.getByRole('button', { name: 'Cease' }));
    await user.click(screen.getByRole('button', { name: 'Yes, cease' }));
    await waitFor(() => expect(body).toEqual({ publicationStatus: 'CEASED', ceasedReason: 'MERGED', lastIssueYear: 2019 }));
  });
  it('ceaseFormOffersTheFourReasonsAndTheServersYears', async () => {
    useDetail();
    page();
    const user = userEvent.setup();
    const reason = await screen.findByLabelText(/Reason/);
    expect([...(reason as HTMLSelectElement).options].map((o) => o.value)).toEqual(['', 'STOPPED', 'TITLE_CHANGE', 'MERGED', 'UNKNOWN']);
    await user.selectOptions(reason, 'STOPPED');
    const year = screen.getByLabelText('Last issue year');
    await user.type(year, '1799');
    expect(screen.getByRole('button', { name: 'Cease' })).toBeDisabled();
    await user.clear(year);
    await user.type(year, String(new Date().getUTCFullYear() + 1));
    expect(screen.getByRole('button', { name: 'Cease' })).toBeDisabled();
    await user.clear(year);
    await user.type(year, '1800');
    expect(screen.getByRole('button', { name: 'Cease' })).toBeEnabled();
  });
  it('activatingAnUnverifiedRouteAsksFirst', async () => {
    useDetail(detail({ routes: [{ id: 7, layer: 'OAI', status: 'PENDING', origin: 'REGISTERED', config: { base: 'https://x.example/oai' } }] }));
    let calls = 0; let body: unknown;
    server.use(http.patch('/api/admin/sources/5/routes/7', async ({ request }) => { calls++; body = await request.json(); return HttpResponse.json({ id: 7 }); }));
    page();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Activate' }));
    expect(calls).toBe(0);
    await user.click(screen.getByRole('button', { name: 'Yes, activate' }));
    await waitFor(() => expect(body).toEqual({ status: 'ACTIVE' }));
  });
  it('disciplineOverrideSaves', async () => {
    useDetail();
    let body: unknown;
    server.use(http.patch('/api/admin/sources/5', async ({ request }) => { body = await request.json(); return HttpResponse.json(detail().source); }));
    page();
    const user = userEvent.setup();
    await user.click(await screen.findByLabelText('Insect Science'));
    await user.click(screen.getByRole('button', { name: 'Save override' }));
    await waitFor(() => expect(body).toEqual({ disciplines: ['1103', '1109'] }));
    await user.click(screen.getByRole('button', { name: 'Release to derived' }));
    await waitFor(() => expect(body).toEqual({ releaseDisciplines: true }));
  });
  it('addRoutePostsConfig', async () => {
    useDetail();
    let body: unknown;
    server.use(http.post('/api/admin/sources/5/routes', async ({ request }) => { body = await request.json(); return HttpResponse.json({ id: 9 }, { status: 202 }); }));
    page();
    const user = userEvent.setup();
    const cfg = await screen.findByLabelText('Config (JSON)');
    await user.clear(cfg);
    await user.click(cfg);
    await user.paste('{"baseUrl":"https://x.example/oai"}');
    await user.selectOptions(screen.getByLabelText('Layer'), 'MAGTECH');
    await user.click(screen.getByRole('button', { name: 'Add route' }));
    await waitFor(() => expect(body).toEqual({ layer: 'MAGTECH', config: { baseUrl: 'https://x.example/oai' } }));
  });
  it('refusesInvalidRouteJsonWithoutCalling', async () => {
    useDetail();
    page();
    const user = userEvent.setup();
    const cfg = await screen.findByLabelText('Config (JSON)');
    await user.clear(cfg);
    await user.click(cfg);
    await user.paste('nope');
    await user.click(screen.getByRole('button', { name: 'Add route' }));
    expect(await screen.findByText('The config is not valid JSON.')).toBeInTheDocument();
  });
});
