import { screen, waitFor } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { theme } from '../theme';
import { renderApp } from '../test/render';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TranslateButton } from './TranslateButton';

afterEach(() => vi.unstubAllGlobals());

describe('TranslateButton', () => {
  it('showsTranslationBelowOriginal', async () => {
    vi.stubGlobal('Translator', {
      availability: async () => 'available',
      create: async () => ({ translate: async () => 'Hello world' }),
    });
    renderApp(<TranslateButton text="你好世界" lang="zh" />);
    await userEvent.click(await screen.findByRole('button', { name: /Translate from Chinese/ }));
    expect(await screen.findByText('Hello world')).toBeInTheDocument();
    expect(screen.getByText('Machine translation, done in your browser')).toBeInTheDocument();
  });

  it('hintWhenUnavailable', async () => {
    renderApp(<TranslateButton text="你好" lang="zh" />);
    expect(await screen.findByText(/use its Translate option/)).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('noFetchIsMade', async () => {
    const f = vi.fn();
    vi.stubGlobal('fetch', f);
    vi.stubGlobal('Translator', {
      availability: async () => 'available',
      create: async () => ({ translate: async () => 'x' }),
    });
    renderApp(<TranslateButton text="a" lang="de" />);
    await userEvent.click(await screen.findByRole('button'));
    await waitFor(() => expect(screen.getByText('x')).toBeInTheDocument());
    expect(f).not.toHaveBeenCalled();
  });

  it('resetsWhenTheTextChanges', async () => {
    vi.stubGlobal('Translator', {
      availability: async () => 'available',
      create: async () => ({ translate: async () => 'old result' }),
    });
    const { rerender } = renderApp(<TranslateButton text="a" lang="de" />);
    await userEvent.click(await screen.findByRole('button'));
    expect(await screen.findByText('old result')).toBeInTheDocument();
    rerender(<MantineProvider theme={theme}><QueryClientProvider client={new QueryClient()}><MemoryRouter><TranslateButton text="b" lang="de" /></MemoryRouter></QueryClientProvider></MantineProvider>);
    await waitFor(() => expect(screen.queryByText('old result')).toBeNull());
  });
});
