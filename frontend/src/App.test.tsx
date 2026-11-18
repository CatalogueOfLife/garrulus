import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { App } from './App';
import { server, emptyPage, meBody, signedOut, taxGroupHandler, xrHandler, sourcesHandler, work } from './test/server';
import { renderApp } from './test/render';

describe('App', () => {
  it('shows the brand and the search page, titled Garrulus', async () => {
    server.use(signedOut, xrHandler, sourcesHandler(), http.get('/api/works', () => HttpResponse.json(emptyPage)), taxGroupHandler);
    renderApp(<App />);
    expect(screen.getByRole('heading', { name: 'Garrulus' })).toBeInTheDocument();
    expect(await screen.findByText(/No works match/)).toBeInTheDocument();
    expect(document.title).toBe('Garrulus — Search');
  });

  it('puts the big header before the main landmark on the search page, with no skipped heading level', async () => {
    server.use(signedOut, xrHandler, sourcesHandler(), taxGroupHandler,
      http.get('/api/works', () => HttpResponse.json({ ...emptyPage, total: 1, results: [work(1, 'Carabid revision')] })));
    renderApp(<App />);
    await screen.findByText('Carabid revision');
    const main = screen.getByRole('main');
    const banner = screen.getByRole('banner');
    expect(main).not.toContainElement(banner);
    expect(banner).toContainElement(screen.getByRole('heading', { level: 1, name: 'Garrulus' }));
    expect(banner).toContainElement(screen.getByRole('navigation', { name: 'Site' }));
    // headings go down one level at a time: h1, then h2 before any h3
    const levels = Array.from(document.querySelectorAll('h1, h2, h3, h4, h5, h6')).map((h) => Number(h.tagName[1]));
    levels.forEach((l, i) => expect(l - (i ? levels[i - 1] : 0)).toBeLessThanOrEqual(1));
  });

  it('routes /source/:id to the journal page', async () => {
    server.use(signedOut, xrHandler, taxGroupHandler, http.get('/api/sources/5', () => HttpResponse.json({
      id: 5, title: 'Zootaxa', tier: 'CORE', subfields: [], taxgroups: [], codes: [], years: [], recentWorks: [],
    })));
    renderApp(<App />, '/source/5');
    expect(await screen.findByRole('heading', { name: 'Zootaxa' })).toBeInTheDocument();
    expect(document.title).toBe('Garrulus — Zootaxa');
  });

  it('shows Sign in when signed out and routes /unsubscribe', async () => {
    server.use(signedOut);
    renderApp(<App />, '/unsubscribe?t=x');
    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Turn off digest emails' })).toBeInTheDocument();
  });

  it('shows the account menu when signed in', async () => {
    server.use(http.get('/api/me', () => HttpResponse.json(meBody)));
    renderApp(<App />, '/unsubscribe?t=x');
    expect(await screen.findByRole('button', { name: 'A' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sign in' })).toBeNull();
  });

  it('shows the not-found page for an unknown path', async () => {
    server.use(signedOut);
    renderApp(<App />, '/no/such/page');
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to the search' })).toHaveAttribute('href', '/');
  });
});
