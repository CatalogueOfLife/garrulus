import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { UnsubscribePage } from './UnsubscribePage';
import { server } from '../test/server';
import { renderApp } from '../test/render';

describe('UnsubscribePage', () => {
  it('posts the token on click, then confirms', async () => {
    let t: string | null = null;
    server.use(http.post('/api/unsubscribe', ({ request }) => {
      t = new URL(request.url).searchParams.get('t');
      return HttpResponse.json({});
    }));
    renderApp(<UnsubscribePage />, '/unsubscribe?t=abc.def');
    await userEvent.click(screen.getByRole('button', { name: 'Turn off digest emails' }));
    expect(await screen.findByText(/Digest emails are off/)).toBeInTheDocument();
    expect(t).toBe('abc.def');
  });

  it('reports a bad link', async () => {
    server.use(http.post('/api/unsubscribe', () => new HttpResponse(null, { status: 400 })));
    renderApp(<UnsubscribePage />, '/unsubscribe?t=x');
    await userEvent.click(screen.getByRole('button', { name: 'Turn off digest emails' }));
    expect(await screen.findByText(/not valid/)).toBeInTheDocument();
  });
});
