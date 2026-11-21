import { screen, waitFor } from '@testing-library/react';
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
    expect(screen.getByText('Translated in your browser')).toBeInTheDocument();
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
});
