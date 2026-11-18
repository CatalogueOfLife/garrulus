import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { FollowButton } from './FollowButton';
import { server, meBody } from '../test/server';
import { renderApp } from '../test/render';

const signedIn = http.get('/api/me', () => HttpResponse.json(meBody));
const sub = (over: object = {}) => ({
  id: 5, type: 'TAXON', target: 'T1', label: 'Carabus', mainSubjectOnly: true, newNamesOnly: false,
  includeAbstractOnly: true, includeReferences: false, filters: {}, sinceYear: null, paused: false,
  feedUrl: 'http://x/feeds/s/tok.atom', created: '2026-11-16T00:00:00Z', ...over,
});

describe('FollowButton', () => {
  it('signedOutGoesToSignIn', async () => {
    renderApp(<FollowButton type="TAXON" target="T1" label="Carabus" />, '/taxon/T1?x=1');
    const link = await screen.findByRole('link', { name: 'Follow' });
    expect(link).toHaveAttribute('href', '/signin?next=' + encodeURIComponent('/taxon/T1?x=1'));
  });

  it('createsWithDefaultsAndSinceYear', async () => {
    let body: Record<string, unknown> | null = null;
    document.cookie = 'XSRF-TOKEN=x1; path=/';
    server.use(
      signedIn,
      http.get('/api/me/subscriptions', () => HttpResponse.json([])),
      http.post('/api/me/subscriptions', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(sub(), { status: 201 });
      }),
    );
    renderApp(<FollowButton type="TAXON" target="T1" label="Carabus" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Follow' }));
    expect(await screen.findByLabelText('Only main subject')).toBeChecked();
    // the dialog's one primary action is the filled pill; Cancel stays an outline pill
    expect(screen.getByRole('button', { name: 'Save' })).toHaveAttribute('data-variant', 'filled');
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveAttribute('data-variant', 'default');
    await userEvent.click(screen.getByLabelText('Only new names'));
    await userEvent.click(screen.getByLabelText('Since year'));
    const year = screen.getByLabelText('Year');
    await userEvent.type(year, '1700');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/between 1750 and/)).toBeInTheDocument();
    expect(body).toBeNull();
    await userEvent.clear(year);
    await userEvent.type(year, '2000');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(body).not.toBeNull());
    expect(body).toMatchObject({
      type: 'TAXON', target: 'T1', label: 'Carabus', mainSubjectOnly: true, newNamesOnly: true,
      includeAbstractOnly: true, includeReferences: false, sinceYear: 2000,
    });
  });

  it('showsLimitMessage', async () => {
    server.use(
      signedIn,
      http.get('/api/me/subscriptions', () => HttpResponse.json([])),
      http.post('/api/me/subscriptions', () => HttpResponse.json({ error: 'limit' }, { status: 409 })),
    );
    renderApp(<FollowButton type="SOURCE" target="7" label="Zootaxa" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Follow' }));
    expect(await screen.findByLabelText('Only main subject')).not.toBeChecked();
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('You follow 100 things already')).toBeInTheDocument();
  });

  it('editsAlreadyFollowedAndSwitchesOnExists', async () => {
    let patch: Record<string, unknown> | null = null;
    server.use(
      signedIn,
      http.get('/api/me/subscriptions', () => HttpResponse.json([sub({ sinceYear: 2010 })])),
      http.patch('/api/me/subscriptions/5', async ({ request }) => {
        patch = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(sub());
      }),
    );
    renderApp(<FollowButton type="TAXON" target="T1" label="Carabus" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Following' }));
    expect(await screen.findByLabelText('Year')).toHaveValue('2010');
    await userEvent.click(screen.getByLabelText('Last 90 days'));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(patch).not.toBeNull());
    expect(patch).toMatchObject({ sinceYear: null, mainSubjectOnly: true });
  });

  it('followingStateIsAFilledPillAndFollowIsOutline', async () => {
    server.use(signedIn, http.get('/api/me/subscriptions', () => HttpResponse.json([sub()])));
    renderApp(<FollowButton type="TAXON" target="T1" label="Carabus" />);
    expect(await screen.findByRole('button', { name: 'Following' })).toHaveAttribute('data-variant', 'filled');
  });

  it('exists409SwitchesToEditing', async () => {
    let subs: object[] = [];
    server.use(
      signedIn,
      http.get('/api/me/subscriptions', () => HttpResponse.json(subs)),
      http.post('/api/me/subscriptions', () => {
        subs = [sub()];
        return HttpResponse.json({ error: 'exists', id: 5 }, { status: 409 });
      }),
    );
    renderApp(<FollowButton type="TAXON" target="T1" label="Carabus" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Follow' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('button', { name: 'Unfollow' })).toBeInTheDocument();
  });

  it('unfollowAsksFirst', async () => {
    let deleted = 0;
    server.use(
      signedIn,
      http.get('/api/me/subscriptions', () => HttpResponse.json(deleted ? [] : [sub()])),
      http.delete('/api/me/subscriptions/5', () => { deleted++; return new HttpResponse(null, { status: 204 }); }),
    );
    renderApp(<FollowButton type="TAXON" target="T1" label="Carabus" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Following' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Unfollow' }));
    expect(screen.getByText(/Stop following Carabus\?/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Keep' }));
    expect(deleted).toBe(0);
    await userEvent.click(screen.getByRole('button', { name: 'Unfollow' }));
    await userEvent.click(screen.getByRole('button', { name: 'Yes, unfollow' }));
    await waitFor(() => expect(deleted).toBe(1));
  });

  it('narrowsBySubfieldName', async () => {
    let patch: Record<string, unknown> | null = null;
    server.use(
      signedIn,
      http.get('/api/me/subscriptions', () => HttpResponse.json([sub({ filters: { subfield: '1109' } })])),
      http.patch('/api/me/subscriptions/5', async ({ request }) => {
        patch = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(sub());
      }),
    );
    renderApp(<FollowButton type="TAXON" target="T1" label="Carabus" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Following' }));
    const select = (await screen.findByLabelText('Subfield')) as HTMLSelectElement;
    expect(select.value).toBe('1109');
    expect(screen.getByRole('option', { name: 'Insect Science' })).toHaveAttribute('title', '1109');
    await userEvent.selectOptions(select, 'Plant Science');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(patch).not.toBeNull());
    expect(patch).toMatchObject({ filters: { subfield: '1110' } });
  });
});
