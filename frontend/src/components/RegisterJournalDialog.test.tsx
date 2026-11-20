import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RegisterJournalDialog } from './RegisterJournalDialog';
import { renderApp } from '../test/render';
import { server } from '../test/server';

const reg = (over: object = {}) => ({
  id: 9, kind: 'ISSN', input: '1175-5326', state: 'IDENTIFIED', outcome: 'EXISTING', status: 'COVERED', created: '2026-11-20T10:00:00Z',
  journal: { id: 7, title: 'Zootaxa', issns: ['1175-5326', '1175-5334'], publisher: 'Magnolia Press', tier: 'CORE', publicationStatus: 'ACTIVE', routes: [] },
  ...over,
});

async function open() {
  renderApp(<RegisterJournalDialog signedIn />, '/journals');
  fireEvent.click(screen.getByRole('button', { name: 'Register a journal' }));
  return await screen.findByRole('dialog');
}
const submit = (text: string) => {
  fireEvent.change(screen.getByRole('textbox', { name: 'ISSN' }), { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: 'Register' }));
};

describe('RegisterJournalDialog', () => {
  afterEach(() => vi.useRealTimers());

  it('signedOutLinksToSignIn', () => {
    renderApp(<RegisterJournalDialog signedIn={false} />, '/journals');
    expect(screen.getByRole('link', { name: 'Register a journal' })).toHaveAttribute('href', '/signin?next=%2Fjournals');
  });

  it('postsIssnAndShowsIdentifiedJournal', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let body: unknown = null;
    server.use(
      http.post('/api/sources/register', async ({ request }) => { body = await request.json(); return HttpResponse.json({ id: 9 }, { status: 202 }); }),
      http.get('/api/me/registrations/9', () => HttpResponse.json(reg())),
    );
    await open();
    submit('1175 5326');
    await waitFor(() => expect(body).toEqual({ issn: '1175 5326' }));
    await act(async () => { await vi.advanceTimersByTimeAsync(2100); });
    expect(await screen.findByText('Zootaxa')).toBeInTheDocument();
    expect(screen.getByText(/1175-5326, 1175-5334/)).toBeInTheDocument();
    expect(screen.getByText('Magnolia Press')).toBeInTheDocument();
    expect(screen.getByText('Covered by Garrulus')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Done' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Not this journal' })).toBeInTheDocument();
  });

  it('stopsPollingAfterThirtySeconds', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let polls = 0;
    server.use(
      http.post('/api/sources/register', () => HttpResponse.json({ id: 9 }, { status: 202 })),
      http.get('/api/me/registrations/9', () => { polls++; return HttpResponse.json(reg({ state: 'IDENTIFYING', status: 'IDENTIFYING', journal: null })); }),
    );
    await open();
    submit('1175-5326');
    await screen.findByText('Identifying the journal…');
    await act(async () => { await vi.advanceTimersByTimeAsync(31_000); });
    expect(await screen.findByText(/Still identifying. The result will appear under Registered journals on your account page./)).toBeInTheDocument();
    const seen = polls;
    expect(seen).toBeGreaterThan(5);
    expect(seen).toBeLessThanOrEqual(16);
    await act(async () => { await vi.advanceTimersByTimeAsync(20_000); });
    expect(polls).toBe(seen);
  });

  it('showsNotIdentifiedReason', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    server.use(
      http.post('/api/sources/register', () => HttpResponse.json({ id: 9 }, { status: 202 })),
      http.get('/api/me/registrations/9', () => HttpResponse.json(reg({ state: 'NOT_IDENTIFIED', status: 'NOT_IDENTIFIED', reason: 'ADDRESS_NOT_PUBLIC', journal: null }))),
    );
    await open();
    submit('1175-5326');
    await act(async () => { await vi.advanceTimersByTimeAsync(2100); });
    expect(await screen.findByText('That address is not a public web page.')).toBeInTheDocument();
  });

  it('notThisJournalWithdraws', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let deleted = 0;
    server.use(
      http.post('/api/sources/register', () => HttpResponse.json({ id: 9 }, { status: 202 })),
      http.get('/api/me/registrations/9', () => HttpResponse.json(reg())),
      http.delete('/api/me/registrations/9', () => { deleted++; return new HttpResponse(null, { status: 204 }); }),
    );
    await open();
    submit('1175-5326');
    await act(async () => { await vi.advanceTimersByTimeAsync(2100); });
    fireEvent.click(await screen.findByRole('button', { name: 'Not this journal' }));
    await waitFor(() => expect(deleted).toBe(1));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('badIssnShowsServerError', async () => {
    server.use(http.post('/api/sources/register', () =>
      HttpResponse.json({ status: 400, error: 'not a valid ISSN (eight characters with a correct check digit)' }, { status: 400 })));
    await open();
    submit('1175-5327');
    expect(await screen.findByText(/not a valid ISSN/)).toBeInTheDocument();
    // the form stays for another try
    expect(screen.getByRole('textbox', { name: 'ISSN' })).toBeInTheDocument();
  });

  it('doiAndAddressUseTheirOwnField', async () => {
    let body: unknown = null;
    server.use(http.post('/api/sources/register', async ({ request }) => { body = await request.json(); return HttpResponse.json({ id: 9 }, { status: 202 }); }));
    await open();
    await userEvent.click(screen.getByLabelText('Web address'));
    await userEvent.type(screen.getByRole('textbox', { name: 'Web address' }), 'https://example.org/j');
    await userEvent.type(screen.getByRole('textbox', { name: /Note/ }), 'missing');
    await userEvent.click(screen.getByRole('button', { name: 'Register' }));
    await waitFor(() => expect(body).toEqual({ url: 'https://example.org/j', note: 'missing' }));
  });
});
