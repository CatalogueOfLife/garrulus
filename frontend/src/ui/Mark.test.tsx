import { render, screen } from '@testing-library/react';
import { Mark } from './Mark';

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

  it('gives two marks on one page their own mask ids', () => {
    const { container } = render(
      <>
        <Mark />
        <Mark title="Garrulus" />
      </>,
    );
    const masks = Array.from(container.querySelectorAll('mask'));
    expect(masks).toHaveLength(2);
    const [a, b] = masks.map((m) => m.id);
    expect(a).toBeTruthy();
    expect(a).not.toBe(b);
    const painted = Array.from(container.querySelectorAll('path[mask]')).map((p) => p.getAttribute('mask'));
    expect(painted).toEqual([`url(#${a})`, `url(#${b})`]);
  });

  it('paints in the current colour', () => {
    const { container } = render(<Mark />);
    expect(container.querySelector('path[mask]')).toHaveAttribute('fill', 'currentColor');
  });
});
