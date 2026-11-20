import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { FollowingPage } from './FollowingPage';
import { server, meBody } from '../test/server';
import { renderApp } from '../test/render';

const sub = (id: number, label: string, over: object = {}) => ({
  id, type: 'TAXON', target: `T${id}`, label, mainSubjectOnly: true, newNamesOnly: false,
  includeAbstractOnly: true, includeReferences: false, filters: {}, sinceYear: null, paused: false,
  feedUrl: `http://x/feeds/s/tok${id}.atom`, created: '2026-11-16T00:00:00Z', targetStatus: 'OK', xrNote: null, previousTarget: null, ...over,
});

describe('FollowingPage', () => {
  it('pauseAndDelete', async () => {
    let subs = [sub(1, 'Carabus'), sub(2, 'Zootaxa', { type: 'SOURCE', target: '7' })];
    let patch: unknown = null;
    let deleted = 0;
    server.use(
      http.get('/api/me', () => HttpResponse.json(meBody)),
      http.get('/api/me/subscriptions', () => HttpResponse.json(subs)),
      http.patch('/api/me/subscriptions/1', async ({ request }) => {
        patch = await request.json();
        subs = [sub(1, 'Carabus', { paused: true }), subs[1]];
        return HttpResponse.json(subs[0]);
      }),
      http.delete('/api/me/subscriptions/2', () => {
        deleted = 2;
        subs = [subs[0]];
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderApp(<FollowingPage />, '/following');
    const row1 = (await screen.findByText('Carabus')).closest('li') as HTMLElement;
    expect(within(row1).getByText('Taxon')).toBeInTheDocument();
    await userEvent.click(within(row1).getByRole('switch', { name: 'Paused' }));
    await waitFor(() => expect(patch).toEqual({ paused: true }));
    await waitFor(() => expect(within(row1).getByRole('switch', { name: 'Paused' })).toBeChecked());
    const row2 = screen.getByText('Zootaxa').closest('li') as HTMLElement;
    // delete asks first; Keep backs out
    await userEvent.click(within(row2).getByRole('button', { name: 'Delete' }));
    expect(within(row2).getByText(/Delete this follow\?/)).toBeInTheDocument();
    await userEvent.click(within(row2).getByRole('button', { name: 'Keep' }));
    expect(deleted).toBe(0);
    await userEvent.click(within(row2).getByRole('button', { name: 'Delete' }));
    await userEvent.click(within(row2).getByRole('button', { name: 'Yes, delete' }));
    await waitFor(() => expect(deleted).toBe(2));
    await waitFor(() => expect(screen.queryByText('Zootaxa')).toBeNull());
  });

  it('shows the notes of moved and missing follows', async () => {
    server.use(
      http.get('/api/me', () => HttpResponse.json(meBody)),
      http.get('/api/me/subscriptions', () => HttpResponse.json([
        sub(1, 'Carabus', { targetStatus: 'MOVED', previousTarget: 'OLD', xrNote: 'Moved to Carabus s.str.' }),
        sub(2, 'Gonus', { targetStatus: 'MISSING', xrNote: 'Gonus is not in the new release.' }),
        sub(3, 'Plain'),
      ])),
    );
    renderApp(<FollowingPage />, '/following');
    const moved = (await screen.findByText('Carabus')).closest('li') as HTMLElement;
    expect(within(moved).getByText('Moved to Carabus s.str.')).toBeInTheDocument();
    const missing = screen.getByText('Gonus').closest('li') as HTMLElement;
    expect(within(missing).getByText(/Gonus is not in the new release\./)).toBeInTheDocument();
    expect(within(missing).getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    const plain = screen.getByText('Plain').closest('li') as HTMLElement;
    expect(within(plain).queryByRole('note')).toBeNull();
  });

  it('copies the feed url', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    server.use(
      http.get('/api/me', () => HttpResponse.json(meBody)),
      http.get('/api/me/subscriptions', () => HttpResponse.json([sub(1, 'Carabus')])),
    );
    renderApp(<FollowingPage />, '/following');
    await userEvent.click(await screen.findByRole('button', { name: 'Copy feed URL' }));
    expect(writeText).toHaveBeenCalledWith('http://x/feeds/s/tok1.atom');
    expect(await screen.findByText('Copied')).toBeInTheDocument();
  });

  it('makes a new feed url after a confirmation', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    let subs = [sub(1, 'Carabus')];
    let posted = 0;
    server.use(
      http.get('/api/me', () => HttpResponse.json(meBody)),
      http.get('/api/me/subscriptions', () => HttpResponse.json(subs)),
      http.post('/api/me/subscriptions/1/feed-token', () => {
        posted++;
        subs = [sub(1, 'Carabus', { feedUrl: 'http://x/feeds/s/fresh.atom' })];
        return HttpResponse.json(subs[0]);
      }),
    );
    renderApp(<FollowingPage />, '/following');
    await userEvent.click(await screen.findByRole('button', { name: 'New feed URL' }));
    expect(screen.getByText(/old feed URL stops working/)).toBeInTheDocument();
    expect(posted).toBe(0);
    await userEvent.click(screen.getByRole('button', { name: 'Make a new URL' }));
    await waitFor(() => expect(posted).toBe(1));
    expect(await screen.findByText('http://x/feeds/s/fresh.atom')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Copy feed URL' }));
    expect(writeText).toHaveBeenCalledWith('http://x/feeds/s/fresh.atom');
  });

  it('showsCeasedBadgeOnASourceFollow', async () => {
    server.use(
      http.get('/api/me', () => HttpResponse.json(meBody)),
      http.get('/api/me/subscriptions', () => HttpResponse.json([
        sub(2, 'Old Journal', { type: 'SOURCE', target: '7', publicationStatus: 'CEASED', lastIssueYear: 2015, continuedBy: { id: 9, title: 'New Journal' } }),
        sub(3, 'Zootaxa', { type: 'SOURCE', target: '8', publicationStatus: 'ACTIVE' }),
      ])),
    );
    renderApp(<FollowingPage />, '/following');
    const row = (await screen.findByText('Old Journal')).closest('li') as HTMLElement;
    expect(within(row).getByText(/Ceased publication · last issue 2015/)).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: 'New Journal' })).toHaveAttribute('href', '/source/9');
    expect(within(screen.getByText('Zootaxa').closest('li') as HTMLElement).queryByText(/Ceased/)).toBeNull();
  });
});
