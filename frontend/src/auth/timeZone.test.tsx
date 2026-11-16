import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { App } from '../App';
import { meBody, server } from '../test/server';
import { renderApp } from '../test/render';

const realOptions = Intl.DateTimeFormat.prototype.resolvedOptions;

function browserIn(zone: string) {
  vi.spyOn(Intl.DateTimeFormat.prototype, 'resolvedOptions').mockImplementation(function (this: Intl.DateTimeFormat) {
    return { ...realOptions.call(this), timeZone: zone };
  });
}

/** /api/me answers this account (until a PATCH changes it); returns the PATCH bodies. */
function account(over: object) {
  const patches: unknown[] = [];
  let me = { ...meBody, ...over };
  server.use(
    http.get('/api/me', () => HttpResponse.json(me)),
    http.patch('/api/me', async ({ request }) => {
      const body = (await request.json()) as object;
      patches.push(body);
      me = { ...me, ...body };
      return HttpResponse.json(me);
    }),
  );
  return patches;
}

const minutesAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString();

describe('time zone proposal', () => {
  afterEach(() => vi.restoreAllMocks());

  it('sets the browser zone once for a new account still on UTC', async () => {
    browserIn('Europe/Berlin');
    const patches = account({ id: 41, timeZone: 'UTC', created: minutesAgo(2) });
    const first = renderApp(<App />, '/unsubscribe?t=x');
    await waitFor(() => expect(patches).toEqual([{ timeZone: 'Europe/Berlin' }]));
    first.unmount();

    // even if the zone is UTC again (changed back on another device), it is not proposed twice
    const again = account({ id: 41, timeZone: 'UTC', created: minutesAgo(3) });
    renderApp(<App />, '/unsubscribe?t=x');
    expect(await screen.findByRole('button', { name: 'A' })).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(again).toEqual([]);
  });

  it('never proposes to an old account', async () => {
    browserIn('Europe/Berlin');
    const patches = account({ id: 42, timeZone: 'UTC', created: minutesAgo(11) });
    renderApp(<App />, '/unsubscribe?t=x');
    expect(await screen.findByRole('button', { name: 'A' })).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(patches).toEqual([]);
  });

  it('leaves a zone that is not UTC, and a browser on UTC', async () => {
    browserIn('Europe/Berlin');
    const chosen = account({ id: 43, timeZone: 'America/Lima', created: minutesAgo(1) });
    const a = renderApp(<App />, '/unsubscribe?t=x');
    expect(await screen.findByRole('button', { name: 'A' })).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(chosen).toEqual([]);
    a.unmount();

    vi.restoreAllMocks();
    browserIn('UTC');
    const utc = account({ id: 44, timeZone: 'UTC', created: minutesAgo(1) });
    renderApp(<App />, '/unsubscribe?t=x');
    expect(await screen.findByRole('button', { name: 'A' })).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(utc).toEqual([]);
  });
});
