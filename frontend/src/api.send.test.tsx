import { http, HttpResponse } from 'msw';
import { send } from './api';
import { server } from './test/server';

describe('api.send', () => {
  it('addsXsrfHeader', async () => {
    document.cookie = 'XSRF-TOKEN=abc%3D123; path=/';
    let seen: string | null = null;
    server.use(http.post('/api/me/email', ({ request }) => {
      seen = request.headers.get('x-xsrf-token');
      return HttpResponse.json({}, { status: 202 });
    }));
    await send('POST', '/api/me/email', { email: 'a@b.c' });
    expect(seen).toBe('abc=123');
  });
});
