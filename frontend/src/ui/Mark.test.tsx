import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { render, screen } from '@testing-library/react';
import { Mark } from './Mark';

const squash = (d: string) => d.replace(/\s+/g, ' ').trim();

describe('Mark', () => {
  it('is an image named by its title', () => {
    render(<Mark title="Garrulus" />);
    const svg = screen.getByRole('img', { name: 'Garrulus' });
    expect(svg.tagName).toBe('svg');
    expect(svg).not.toHaveAttribute('aria-hidden');
  });

  it('is hidden from assistive technology without a title', () => {
    const { container } = render(<Mark />);
    const svg = container.querySelector('svg')!;
    expect(svg).toHaveAttribute('aria-hidden', 'true');
    expect(svg).not.toHaveAttribute('role');
    expect(screen.queryByRole('img')).toBeNull();
  });

  it('is 38 px by default and takes a size', () => {
    const { container } = render(
      <>
        <Mark />
        <Mark size={96} />
      </>,
    );
    const [small, big] = container.querySelectorAll('svg');
    expect(small).toHaveAttribute('width', '38');
    expect(small).toHaveAttribute('height', '38');
    expect(big).toHaveAttribute('width', '96');
    expect(big).toHaveAttribute('height', '96');
  });

  it('is one even-odd path in the current colour', () => {
    const { container } = render(<Mark />);
    const paths = container.querySelectorAll('svg path');
    expect(paths).toHaveLength(1);
    expect(paths[0]).toHaveAttribute('fill-rule', 'evenodd');
    expect(paths[0]).toHaveAttribute('fill', 'currentColor');
  });

  it('puts no ids on the page, so two marks cannot clash', () => {
    const { container } = render(
      <>
        <Mark />
        <Mark title="Garrulus" />
      </>,
    );
    expect(container.querySelectorAll('svg')).toHaveLength(2);
    expect(container.querySelectorAll('[id]')).toHaveLength(0);
  });

  it('draws the same path as the brand file docs/brand/garrulus-mark.svg', () => {
    const svg = readFileSync(resolve(__dirname, '../../../docs/brand/garrulus-mark.svg'), 'utf8');
    const brand = /<path[^>]*\sd="([^"]+)"/.exec(svg)?.[1];
    expect(brand).toBeDefined();
    const { container } = render(<Mark />);
    expect(squash(container.querySelector('path')!.getAttribute('d')!)).toBe(squash(brand!));
  });
});
