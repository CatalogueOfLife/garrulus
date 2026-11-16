import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { FollowingPage } from './FollowingPage';
import { server, meBody } from '../test/server';
import { renderApp } from '../test/render';

const sub = (id: number, label: string, over: object = {}) => ({
  id, type: 'TAXON', target: `T${id}`, label, mainSubjectOnly: true, newNamesOnly: false,
  includeAbstractOnly: true, includeReferences: false, filters: {}, sinceYear: null, paused: false,
  feedUrl: `http://x/feeds/s/tok${id}.atom`, created: '2026-11-16T00:00:00Z', ...over,
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
    await userEvent.click(within(row2).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(deleted).toBe(2));
    await waitFor(() => expect(screen.queryByText('Zootaxa')).toBeNull());
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
});
