import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

describe('index.html', () => {
  it('sets the colour scheme in the head, before the bundle runs', () => {
    const html = readFileSync(resolve(__dirname, '../index.html'), 'utf8');
    const head = html.slice(0, html.indexOf('</head>'));
    expect(head).toMatch(/<script data-mantine-script>[\s\S]*setAttribute\("data-mantine-color-scheme"/);
    // the same default as the provider in main.tsx
    expect(head).toContain(': "auto";');
    expect(html.indexOf('data-mantine-script')).toBeLessThan(html.indexOf('/src/main.tsx'));
  });
});
