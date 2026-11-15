import { screen } from '@testing-library/react';
import { YearBars } from './YearBars';
import { renderApp } from '../test/render';

describe('YearBars', () => {
  it('yearBarsRenderOneBarPerYear', () => {
    renderApp(<YearBars years={[{ year: 2024, works: 2 }, { year: 2025, works: 8 }, { year: 2026, works: 4 }]} />);
    const bars = screen.getAllByTestId('year-bar');
    expect(bars).toHaveLength(3);
    expect(bars[1]).toHaveAttribute('aria-label', '2025: 8 works');
    expect(bars[1].getAttribute('height')).toBe('100');
    expect(bars[0].getAttribute('height')).toBe('25');
  });
  it('fillsGapYearsWithZeroBars', () => {
    renderApp(<YearBars years={[{ year: 2020, works: 3 }, { year: 2023, works: 3 }]} />);
    const bars = screen.getAllByTestId('year-bar');
    expect(bars).toHaveLength(4);
    expect(bars[1]).toHaveAttribute('aria-label', '2021: 0 works');
  });
  it('rendersNothingForNoYears', () => {
    renderApp(<YearBars years={[]} />);
    expect(screen.queryAllByTestId('year-bar')).toHaveLength(0);
  });
});
