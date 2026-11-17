import { act, fireEvent, screen } from '@testing-library/react';
import { YearBars, labelIndices, niceTicks } from './YearBars';
import { renderApp } from '../test/render';

describe('YearBars', () => {
  it('yearBarsRenderOneBarPerYear', () => {
    renderApp(<YearBars years={[{ year: 2024, works: 2 }, { year: 2025, works: 8 }, { year: 2026, works: 4 }]} />);
    const bars = screen.getAllByTestId('year-bar');
    expect(bars).toHaveLength(3);
    expect(bars[1]).toHaveAttribute('aria-label', '2025: 8 papers');
    expect(Number(bars[1].getAttribute('height'))).toBeGreaterThan(Number(bars[0].getAttribute('height')) * 3.9);
  });
  it('fillsGapYearsWithZeroBars', () => {
    renderApp(<YearBars years={[{ year: 2020, works: 3 }, { year: 2023, works: 3 }]} />);
    const bars = screen.getAllByTestId('year-bar');
    expect(bars).toHaveLength(4);
    expect(bars[1]).toHaveAttribute('aria-label', '2021: 0 papers');
  });
  it('smallNonZeroBarsKeepAMinimumHeightAndZeroStaysEmpty', () => {
    renderApp(<YearBars years={[{ year: 2024, works: 1 }, { year: 2025, works: 0 }, { year: 2026, works: 1447 }]} />);
    const h = screen.getAllByTestId('year-bar').map((b) => Number(b.getAttribute('height')));
    expect(h[0]).toBeGreaterThanOrEqual(2);
    expect(h[1]).toBe(0);
    expect(h[2]).toBeGreaterThan(100);
  });
  it('rendersNothingForNoYears', () => {
    renderApp(<YearBars years={[]} />);
    expect(screen.queryAllByTestId('year-bar')).toHaveLength(0);
  });
  it('drawsYAxisTicksLabelAndAllYearLabels', () => {
    renderApp(<YearBars years={[{ year: 2024, works: 2 }, { year: 2026, works: 8 }]} />);
    const ticks = screen.getAllByTestId('y-tick').map((t) => t.textContent);
    expect(ticks[0]).toBe('0');
    expect(ticks.length).toBeGreaterThanOrEqual(3);
    expect(ticks.length).toBeLessThanOrEqual(5);
    expect(screen.getByText('Papers')).toBeInTheDocument();
    expect(screen.getAllByTestId('x-label').map((t) => t.textContent)).toEqual(['2024', '2025', '2026']);
  });
  it('showsReadoutOnHoverAndFocusSingularAndPlural', () => {
    renderApp(<YearBars years={[{ year: 2025, works: 1 }, { year: 2026, works: 4 }]} />);
    const [a, b] = screen.getAllByTestId('year-bar');
    expect(screen.getByTestId('year-readout')).toHaveTextContent('');
    fireEvent.mouseEnter(b);
    expect(screen.getByTestId('year-readout')).toHaveTextContent('2026: 4 papers');
    fireEvent.mouseLeave(b);
    expect(screen.getByTestId('year-readout')).toHaveTextContent('');
    act(() => a.focus());
    expect(screen.getByTestId('year-readout')).toHaveTextContent('2025: 1 paper');
    expect(screen.getByTestId('year-readout').textContent).not.toMatch(/papers/);
  });
  it('marksTheSelectedRangeWithGrCurrentAndLeavesTheOthersInk', () => {
    renderApp(<YearBars years={[{ year: 2024, works: 2 }, { year: 2025, works: 8 }, { year: 2026, works: 4 }]} range={{ from: 2025, to: 2026 }} />);
    const bars = screen.getAllByTestId('year-bar');
    expect(bars.map((b) => b.classList.contains('gr-current'))).toEqual([false, true, true]);
  });
  it('marksNoBarWithoutARange', () => {
    renderApp(<YearBars years={[{ year: 2024, works: 2 }, { year: 2025, works: 8 }]} />);
    expect(screen.getAllByTestId('year-bar').some((b) => b.classList.contains('gr-current'))).toBe(false);
  });
  it('drawsAtTheSideColumnWidthSoTheAxisTextIsNotScaledDown', () => {
    const years = Array.from({ length: 12 }, (_, i) => ({ year: 2015 + i, works: i }));
    const { container } = renderApp(<YearBars years={years} />);
    const width = Number(container.querySelector('svg')!.getAttribute('viewBox')!.split(' ')[2]);
    expect(width).toBeLessThanOrEqual(340);
    // 12 four-digit labels at 11 px do not fit 340 px side by side
    expect(screen.getAllByTestId('x-label').length).toBeLessThanOrEqual(8);
  });
  it('thinsLabelsForManyYearsKeepingFirstAndLast', () => {
    const idx = labelIndices(40);
    expect(idx[0]).toBe(0);
    expect(idx[idx.length - 1]).toBe(39);
    expect(idx.length).toBeLessThanOrEqual(9);
    expect(labelIndices(12)).toHaveLength(12);
  });
  it('niceTicksAreIntegersFromZeroToAtLeastMax', () => {
    for (const max of [0, 1, 3, 4, 7, 23, 101, 5000]) {
      const t = niceTicks(max);
      expect(t[0]).toBe(0);
      expect(t.length).toBeGreaterThanOrEqual(2);
      expect(t.length).toBeLessThanOrEqual(5);
      expect(t[t.length - 1]).toBeGreaterThanOrEqual(max);
      expect(t.every(Number.isInteger)).toBe(true);
    }
  });
});
