import { render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { App } from './App';
import { theme } from './theme';
import { server } from './test/server';

describe('App', () => {
  it('shows the brand and the API ping result', async () => {
    server.use(http.get('/api/ping', () => HttpResponse.text('pong')));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <MantineProvider theme={theme}>
        <QueryClientProvider client={client}>
          <App />
        </QueryClientProvider>
      </MantineProvider>,
    );
    expect(screen.getByRole('heading', { name: 'Garrulus' })).toBeInTheDocument();
    expect(await screen.findByText('API: pong')).toBeInTheDocument();
  });
});
