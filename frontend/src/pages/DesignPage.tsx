import { useState, type ReactNode } from 'react';
import {
  ActionIcon, Alert, Anchor, Badge, Burger, Button, Checkbox, CloseButton, Collapse, Divider, Loader, Menu, Modal,
  NativeSelect, Pagination, Radio, SegmentedControl, Select, Skeleton, Switch, Text, TextInput, Title,
} from '@mantine/core';
import { Link } from 'react-router-dom';
import type { MentionSpan, NameRef, WorkSummary } from '../api';
import tokensCss from '../styles/tokens.css?raw';
import { parseTokens } from '../styles/tokenValues';
import { ActMarker } from '../ui/ActMarker';
import { DetailHead } from '../ui/DetailHead';
import { EmptyState } from '../ui/EmptyState';
import { FacetGroup } from '../ui/FacetGroup';
import { Mark } from '../ui/Mark';
import { MetaLine } from '../ui/MetaLine';
import { NameMark } from '../ui/NameMark';
import { Rule } from '../ui/Rule';
import { SciName } from '../ui/SciName';
import { SciTitle } from '../ui/SciTitle';
import { SideColumn } from '../ui/SideColumn';
import { StatRows } from '../ui/StatRows';
import { WorkItem } from '../ui/WorkItem';
import frame from '../ui/frame.module.css';
import title from '../ui/title.module.css';
import { usePageTitle } from '../usePageTitle';
import styles from './design.module.css';

const tokens = parseTokens(tokensCss);
const COLOURS = ['--gr-paper', '--gr-ink', '--gr-grey', '--gr-rule-soft', '--gr-red'];
const COLOUR_USE: Record<string, string> = {
  '--gr-paper': 'page background',
  '--gr-ink': 'text, rules, logo, outlines',
  '--gr-grey': 'secondary text',
  '--gr-rule-soft': 'minor separator in dense blocks',
  '--gr-red': 'new names, current place, errors, focus',
};

const SCALE = [
  { token: '--gr-type-display', key: 'display', weight: 500, tracking: 'var(--gr-type-display-tracking)', text: 'Garrulus' },
  { token: '--gr-type-title', key: 'title', weight: 500, tracking: 'var(--gr-type-title-tracking)', text: 'Names in the literature' },
  { token: '--gr-type-lede', key: 'lede', weight: 400, tracking: '-0.01em', text: 'Garrulus follows the journals of biodiversity science and finds the names in them.' },
  { token: '--gr-type-head', key: 'head', weight: 500, tracking: '-0.01em', text: <>A new species of <i>Hemiphyllodactylus</i> from northern Vietnam</> },
  { token: '--gr-type-body', key: 'body', weight: 400, tracking: 'normal', text: 'Running text, such as the full text of an open-access article.' },
  { token: '--gr-type-ui', key: 'ui', weight: 400, tracking: 'normal', text: 'Navigation, filters, names lists and buttons.' },
  { token: '--gr-type-meta', key: 'meta', weight: 400, tracking: 'normal', text: 'Dinh, Nishikawa, Luu et al. ZooKeys 1297: 311–337 16 Nov 2026' },
  { token: '--gr-type-small', key: 'small', weight: 500, tracking: 'normal', text: 'Facet group headings, footnotes and the header line.' },
];

// The sample works of mockup B. Offsets are code points into the title; each title genus is a main name, as in the
// real lists, so its rank sets the title's italics.
let nextId = 1;
function mentionsIn(title: string, names: { text: string; nameId: number }[]): MentionSpan[] {
  return names.map(({ text, nameId }) => {
    const startCp = Array.from(title.slice(0, title.indexOf(text))).length;
    return { id: nextId++, nameId, startCp, endCp: startCp + Array.from(text).length, verbatim: text };
  });
}
const name = (id: number, n: string, rank: string, act?: string): NameRef =>
  ({ id, name: n, rank, placementMethod: 'MATCH', actTypes: act ? [act] : [] });

const T1 = 'A new species of Hemiphyllodactylus Bleeker, 1860 (Squamata, Gekkonidae) from northern Vietnam';
const T2 = 'Integrative taxonomy of Tenumembrana Luo & Wang, 2025 (Blattodea, Blattidae), with clarification of its type species and descriptions of two new species';
const T3 = 'Revision of the New World Syntelia Westwood (Coleoptera: Histeroidea: Synteliidae) with description of two new species, key and checklist of the family';

const SAMPLE_WORKS: WorkSummary[] = [
  {
    id: 1, title: T1, year: 2026, publicationDate: '2026-11-16', source: { id: 1, title: 'ZooKeys' },
    volume: '1297', pages: '311-337', authors: ['Dinh', 'Nishikawa', 'Luu'], authorCount: 5, textLevel: 'FULLTEXT',
    hasActs: true,
    mainNames: [name(11, 'Hemiphyllodactylus baviensis', 'species', 'SP_NOV'), name(10, 'Hemiphyllodactylus', 'genus')],
    titleMentions: mentionsIn(T1, [
      { text: 'Hemiphyllodactylus', nameId: 10 }, { text: 'Squamata', nameId: 12 }, { text: 'Gekkonidae', nameId: 13 },
    ]),
  },
  {
    id: 2, title: T2, year: 2026, publicationDate: '2026-11-16', source: { id: 1, title: 'ZooKeys' },
    volume: '1297', pages: '339-361', authors: ['Luo', 'Wang'], authorCount: 2, textLevel: 'FULLTEXT', hasActs: true,
    mainNames: [
      name(21, 'Tenumembrana parva', 'species', 'SP_NOV'),
      name(22, 'Tenumembrana tenebrosa', 'species', 'SP_NOV'),
      name(23, 'Tenumembrana fallax', 'species', 'COMB_NOV'),
      name(20, 'Tenumembrana', 'genus'),
    ],
    titleMentions: mentionsIn(T2, [
      { text: 'Tenumembrana', nameId: 20 }, { text: 'Blattodea', nameId: 24 }, { text: 'Blattidae', nameId: 25 },
    ]),
  },
  {
    id: 3, title: T3, year: 2026, publicationDate: '2026-11-12', source: { id: 2, title: 'European Journal of Taxonomy' },
    volume: '1122', authors: ['Caterino'], authorCount: 1, textLevel: 'ABSTRACT_ONLY', hasActs: true,
    mainNames: [
      name(31, 'Syntelia tajumulcensis', 'species', 'SP_NOV'), name(32, 'Syntelia undosa', 'species', 'SP_NOV'),
      name(30, 'Syntelia', 'genus'),
    ],
    titleMentions: mentionsIn(T3, [
      { text: 'Syntelia', nameId: 30 }, { text: 'Coleoptera', nameId: 33 }, { text: 'Histeroidea', nameId: 34 },
      { text: 'Synteliidae', nameId: 35 },
    ]),
  },
];

const RUNNING_SPAN: MentionSpan = { id: 900, nameId: 11, startCp: 0, endCp: 0, verbatim: '' };

function Swatches({ scheme }: { scheme: 'light' | 'dark' }) {
  const t = tokens[scheme];
  const dark = scheme === 'dark';
  const body = (
    <div className={styles.half} style={{ background: t['--gr-paper'], color: t['--gr-ink'] }} data-testid={`swatches-${scheme}`}>
      <h3 className={styles.sub} style={{ marginTop: 0 }}>{dark ? 'Dark' : 'Light'}</h3>
      {COLOURS.map((token) => (
        <div key={token} className={styles.swatch} data-testid={`swatch-${scheme}${token}`}>
          <span className={styles.chip} style={{ background: t[token] }} />
          <span>
            <code>{token}</code>
            <span className={styles.hex} style={{ color: t['--gr-grey'] }}>{t[token].toUpperCase()}</span>
            <br />
            <span style={{ color: t['--gr-grey'] }}>{COLOUR_USE[token]}</span>
          </span>
        </div>
      ))}
      <p style={{ margin: '16px 0 0' }}>
        A link <Anchor href="#colour" style={{ color: t['--gr-ink'] }}>in ink</Anchor>, a new name{' '}
        <span style={{ color: t['--gr-red'] }}>sp. nov.</span>
      </p>
    </div>
  );
  return dark ? <div data-mantine-color-scheme="dark">{body}</div> : body;
}

const STATES = ['Default', 'Hover', 'Focus', 'Active', 'Disabled', 'Error'] as const;
type State = (typeof STATES)[number];
type Demo = (state: State) => ReactNode;

function stateClass(state: State): string | undefined {
  return state === 'Hover' ? styles.hover : state === 'Focus' ? styles.focus : state === 'Active' ? styles.active : undefined;
}

const DEMOS: { name: string; demo: Demo; errors?: boolean }[] = [
  { name: 'Button', demo: (s) => <Button disabled={s === 'Disabled'} color={s === 'Error' ? 'red' : undefined}>Follow</Button> },
  { name: 'Button, primary', demo: (s) => <Button variant="filled" disabled={s === 'Disabled'}>Save</Button> },
  { name: 'ActionIcon', demo: (s) => <ActionIcon aria-label="Add" disabled={s === 'Disabled'}>+</ActionIcon> },
  { name: 'CloseButton', demo: (s) => <CloseButton aria-label="Close" disabled={s === 'Disabled'} /> },
  { name: 'TextInput', errors: true, demo: (s) => (
    <TextInput aria-label="Text" placeholder="Name" disabled={s === 'Disabled'} error={s === 'Error' ? 'Required' : undefined} />) },
  { name: 'NativeSelect', errors: true, demo: (s) => (
    <NativeSelect aria-label="Native select" data={['Daily', 'Weekly']} disabled={s === 'Disabled'} error={s === 'Error' ? 'Required' : undefined} />) },
  { name: 'Select', errors: true, demo: (s) => (
    <Select aria-label="Select" data={['Daily', 'Weekly']} placeholder="Pick" disabled={s === 'Disabled'} error={s === 'Error' ? 'Required' : undefined} />) },
  { name: 'Checkbox', errors: true, demo: (s) => (
    <Checkbox label="Option" defaultChecked disabled={s === 'Disabled'} error={s === 'Error' ? 'Required' : undefined} />) },
  { name: 'Radio', errors: true, demo: (s) => (
    <Radio label="Option" defaultChecked disabled={s === 'Disabled'} error={s === 'Error' ? 'Required' : undefined} />) },
  { name: 'Switch', demo: (s) => <Switch aria-label="Switch" label="On" defaultChecked disabled={s === 'Disabled'} /> },
  { name: 'SegmentedControl', demo: (s) => (
    <SegmentedControl aria-label="View" data={['Works', 'Names']} disabled={s === 'Disabled'} />) },
  { name: 'Pagination', demo: (s) => <Pagination total={5} defaultValue={2} size="sm" disabled={s === 'Disabled'} /> },
  { name: 'Badge', demo: (s) => <Badge variant={s === 'Disabled' ? 'outline' : 'default'} color={s === 'Error' ? 'red' : undefined}>Label</Badge> },
  { name: 'Anchor', demo: (s) => <Anchor href="#components" aria-disabled={s === 'Disabled' || undefined}>A link</Anchor> },
  { name: 'Burger', demo: (s) => <Burger aria-label="Menu" size="sm" disabled={s === 'Disabled'} /> },
];

function StateGrid() {
  return (
    <div role="table" aria-label="Mantine component states">
      <div role="row" className={`${styles.states} ${styles.stateHead}`}>
        <span role="columnheader">Component</span>
        {STATES.map((s) => <span role="columnheader" key={s}>{s}</span>)}
      </div>
      {DEMOS.map((d) => (
        <div role="row" className={styles.states} key={d.name}>
          <span role="rowheader" className={styles.compName}>{d.name}</span>
          {STATES.map((s) => (
            <div role="cell" key={s} className={stateClass(s)}>
              {s === 'Error' && !d.errors && d.name !== 'Button' && d.name !== 'Badge' ? <span className={styles.token}>none</span> : d.demo(s)}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

function Overlays() {
  const [modal, setModal] = useState(false);
  const [open, setOpen] = useState(false);
  return (
    <>
      <h3 className={styles.sub}>Menu, modal, alert, collapse</h3>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 32, alignItems: 'flex-start' }}>
        <Menu opened withinPortal={false} trapFocus={false} closeOnEscape={false} middlewares={{ flip: false, shift: false }} zIndex={1}>
          <Menu.Target><Button>Menu</Button></Menu.Target>
          <Menu.Dropdown>
            <Menu.Item>Account</Menu.Item>
            <Menu.Item>Following</Menu.Item>
            <Menu.Item color="red">Delete account</Menu.Item>
          </Menu.Dropdown>
        </Menu>
        <div style={{ marginTop: 130 }}>
          <Button onClick={() => setModal(true)}>Open modal</Button>
          <Modal opened={modal} onClose={() => setModal(false)} title="Delete account">
            This removes your follows and your digests. It cannot be undone.
            <div style={{ marginTop: 16 }}><Button variant="outline" color="red" onClick={() => setModal(false)}>Delete</Button></div>
          </Modal>
        </div>
        <div style={{ marginTop: 130 }}>
          <Button onClick={() => setOpen((o) => !o)}>{open ? 'Hide' : 'Show'} details</Button>
          <Collapse in={open}><Text mt="sm">Collapsed content, shown on demand.</Text></Collapse>
        </div>
      </div>
      <div style={{ maxWidth: 560, marginTop: 24, display: 'grid', gap: 12 }}>
        <Alert title="Saved">Your settings were saved.</Alert>
        <Alert color="red" title="Could not save">The server did not answer.</Alert>
      </div>
    </>
  );
}

function Static() {
  return (
    <>
      <h3 className={styles.sub}>Loader, skeleton, divider, title, text</h3>
      <div style={{ display: 'grid', gap: 16, maxWidth: 560 }}>
        <Loader />
        <Skeleton height={20} />
        <Skeleton height={20} width="60%" />
        <Divider />
        <Title order={2}>Title, order 2</Title>
        <Text>Text in the default size.</Text>
        <Text c="dimmed" size="sm">Dimmed text in the grey.</Text>
      </div>
    </>
  );
}

function Patterns() {
  const [rank, setRank] = useState<string | undefined>('species');
  return (
    <div className={styles.patterns}>
      <div>
        <h3 className={styles.sub} style={{ marginTop: 0 }}>Page title and sub-line</h3>
        <DetailHead
          title={<SciName name="Hemiphyllodactylus baviensis" rank="species" acts={['SP_NOV']} />}
          parts={['Species', 'Gekkonidae', '3 works']}
        />
        <h3 className={styles.sub}>Names in running text</h3>
        <p className={styles.running}>
          The new gecko, <NameMark span={{ ...RUNNING_SPAN, nameId: 11, actType: 'SP_NOV' }} name={{ rank: 'species' }}>Hemiphyllodactylus baviensis</NameMark>,
          differs from <NameMark span={{ ...RUNNING_SPAN, nameId: 14 }} name={{ rank: 'species' }}>H. yunnanensis</NameMark> in
          the family <NameMark span={{ ...RUNNING_SPAN, nameId: 13 }} name={{ rank: 'family' }}>Gekkonidae</NameMark>.
        </p>
        <h3 className={styles.sub}>Empty state</h3>
        <EmptyState title="Nothing here yet" action={<Button component={Link} to="/">Go to the search</Button>}>
          There are no works for this filter.
        </EmptyState>
      </div>
      <SideColumn lede="A side column: the lede under a rule, then the filters.">
        <FacetGroup
          title="Rank"
          current={rank}
          onSelect={setRank}
          items={[
            { value: 'species', label: 'Species', count: 1204 },
            { value: 'genus', label: 'Genus', count: 310 },
            { value: 'family', label: 'Family', count: 58 },
          ]}
        />
        <div style={{ marginTop: 30 }}>
          <Rule />
          <StatRows rows={[{ label: 'Works', value: '1,572' }, { label: 'Journals', value: 12 }, { label: 'First seen', value: '2019' }]} />
        </div>
      </SideColumn>
    </div>
  );
}

const ITALICS: { rule: string; example: ReactNode; style: string }[] = [
  { rule: 'Genus and below', example: <SciName name="Hemiphyllodactylus baviensis" rank="species" />, style: 'Italic' },
  { rule: 'Family and above', example: <SciName name="Gekkonidae" rank="family" />, style: 'Roman' },
  { rule: 'Authorship and qualifiers', example: <><i>Hemiphyllodactylus</i> Bleeker, 1860 <ActMarker type="SP_NOV" /></>, style: 'Roman' },
  { rule: 'Unknown rank, usage rank known', example: <SciName name="Tenumembrana" rank="unranked" usageRank="genus" />, style: 'By usage rank' },
  { rule: 'Unknown rank, binomial or trinomial', example: <SciName name="Syntelia undosa" />, style: 'Italic' },
  { rule: 'Unknown rank, uninomial', example: <SciName name="Syntelia" />, style: 'Roman' },
  { rule: 'OTU codes', example: <SciName name="BOLD:AAA1234" kind="OTU" />, style: 'Roman' },
];

export function DesignPage() {
  usePageTitle('Design');
  return (
    <div className={styles.page}>
      <h1 className={title.pageTitle}>Design specimen</h1>
      <p className={styles.note}>The colours, type, logo and components of Garrulus in one place. Sample data, no API calls.</p>

      <section id="colour">
        <h2>Colour</h2>
        <p className={styles.note}>Black and white with a bit of red. Red means look here: new names, the current place, errors and focus.</p>
        <div className={styles.halves}>
          <Swatches scheme="light" />
          <Swatches scheme="dark" />
        </div>
      </section>

      <section id="type">
        <h2>Type</h2>
        <p className={styles.note}>Schibsted Grotesk, weights 400 and 500. Sentence case throughout.</p>
        {SCALE.map((s) => (
          <div className={styles.scaleRow} key={s.token}>
            <span>
              <code className={styles.token}>{s.token}</code>
            </span>
            <span
              style={{
                fontSize: `var(${s.token}-size)`,
                lineHeight: `var(${s.token}-line)`,
                fontWeight: s.weight,
                letterSpacing: s.tracking,
                overflowWrap: 'anywhere',
              }}
            >
              {s.text}
            </span>
          </div>
        ))}
      </section>

      <section id="logo">
        <h2>Logo</h2>
        <p className={styles.note}>The mark in one colour, at 260, 64, 32 and 16 px, then the compact and the big lockup.</p>
        <div className={styles.markRow}>
          {[260, 64, 32, 16].map((size) => <Mark key={size} size={size} title="Garrulus mark" />)}
        </div>
        <h3 className={styles.sub}>Lockups</h3>
        <div className={styles.markRow}>
          <span className={styles.lockupCompact}>
            <Mark size={38} />
            <span className={styles.wordmark30}>Garrulus</span>
          </span>
          <span className={`${styles.lockup} ${frame.brandBig}`} style={{ padding: 0 }}>
            <Mark size={96} />
            <span className={styles.wordmarkBig}>Garrulus</span>
          </span>
        </div>
      </section>

      <section id="components">
        <h2>Components</h2>
        <h3 className={styles.sub} style={{ marginTop: 0 }}>Mantine components in every state</h3>
        <p className={styles.note}>Hover, focus and active are forced with a class. Rows without an error state say none.</p>
        <StateGrid />
        <Overlays />
        <Static />
        <h3 className={styles.sub}>Works</h3>
        <p className={styles.note}>The three works of mockup B, as they appear in a list.</p>
        <div className={styles.workList}>
          {SAMPLE_WORKS.map((w) => <WorkItem key={w.id} work={w} />)}
          <Rule />
        </div>
        <h3 className={styles.sub}>Title, meta line and names, apart</h3>
        <p style={{ fontSize: 'var(--gr-type-head-size)', fontWeight: 500, margin: '0 0 4px', maxWidth: 760 }}>
          <SciTitle
            title={SAMPLE_WORKS[0].title ?? ''}
            mentions={SAMPLE_WORKS[0].titleMentions ?? []}
            names={new Map([[10, { rank: 'genus' }]])}
          />
        </p>
        <MetaLine authors={['Dinh', 'Nishikawa']} authorCount={5} source={{ id: 1, title: 'ZooKeys' }} volume="1297" pages="311-337" date="2026-11-16" textLevel="FULLTEXT" />
      </section>

      <section id="patterns">
        <h2>Patterns</h2>
        <h3 className={styles.sub} style={{ marginTop: 0 }}>The italics rule</h3>
        <table className={styles.table} aria-label="Italics rule">
          <thead>
            <tr><th scope="col">Case</th><th scope="col">Example</th><th scope="col">Style</th></tr>
          </thead>
          <tbody>
            {ITALICS.map((r) => (
              <tr key={r.rule}><td>{r.rule}</td><td>{r.example}</td><td>{r.style}</td></tr>
            ))}
          </tbody>
        </table>
        <h3 className={styles.sub}>Detail page parts</h3>
        <Patterns />
      </section>
    </div>
  );
}
