import { Alert } from '@mantine/core';
import { screen } from '@testing-library/react';
import { renderApp } from './test/render';

describe('themed Alert', () => {
  it('gives a red alert a defined, red title class', () => {
    renderApp(<Alert color="red" title="Broken">text</Alert>);
    const cls = screen.getByText('Broken').parentElement!.className;
    expect(cls).not.toContain('undefined');
    expect(cls).toMatch(/alertTitleRed/);
  });

  it('gives a plain alert no red class', () => {
    renderApp(<Alert title="Fine">text</Alert>);
    const cls = screen.getByText('Fine').parentElement!.className;
    expect(cls).not.toContain('undefined');
    expect(cls).not.toMatch(/alertTitleRed/);
  });
});
