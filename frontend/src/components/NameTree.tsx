import type { NameDetail } from '../api';
import { SciName } from '../ui/SciName';
import { rankIndex, rootFirst } from './ClassificationPath';
import classes from '../ui/work.module.css';
import list from '../ui/list.module.css';

/** A taxon of the tree (from a classification or a placement) and the names of the work placed exactly on it. */
export interface TreeNode {
  key: string;
  taxon?: { id?: string | null; name: string; rank?: string | null };
  names: NameDetail[];
  children: TreeNode[];
}

const norm = (s?: string | null) => (s ?? '').trim().toLowerCase();

/**
 * The placed names of a work in one tree of their COL classifications. A name matched to a taxon of the same name
 * (the usage name may carry the authorship) is that taxon's node; any other name (placed on its genus, a synonym, a
 * verbatim placement) hangs below its taxon as a leaf. Names without any placement are left out.
 */
export function buildNameTree(names: NameDetail[]): TreeNode {
  const root: TreeNode = { key: '', names: [], children: [] };
  const childOf = (parent: TreeNode, key: string, make: () => TreeNode) => {
    let c = parent.children.find((x) => x.key === key);
    if (!c) {
      c = make();
      parent.children.push(c);
    }
    return c;
  };
  for (const n of names) {
    const chain = rootFirst(n.classification.filter((c) => c.name)).map((c) => ({ id: c.id, name: c.name!, rank: c.rank }));
    if (n.usageId || n.usageName) chain.push({ id: n.usageId, name: n.usageName || n.usageId!, rank: n.usageRank });
    if (!chain.length) continue;
    let node = root;
    for (const t of chain) {
      const key = t.id ? `id:${t.id}` : `n:${norm(t.rank)}|${norm(t.name)}`;
      node = childOf(node, key, () => ({ key, taxon: t, names: [], children: [] }));
    }
    const usage = norm(n.usageName);
    const same = (n.usageId || n.usageName) && n.placementMethod === 'MATCH'
      && (usage === norm(n.name) || usage.startsWith(`${norm(n.name)} `));
    if (same) node.names.push(n);
    else node.children.push({ key: `name:${n.id}`, names: [n], children: [] });
  }
  return root;
}

const order = (a: TreeNode, b: TreeNode) => {
  const ra = rankIndex(a.taxon?.rank ?? a.names[0]?.rank);
  const rb = rankIndex(b.taxon?.rank ?? b.names[0]?.rank);
  if (ra !== rb) return (ra < 0 ? 999 : ra) - (rb < 0 ? 999 : rb);
  return (a.taxon?.name ?? a.names[0]?.name ?? '').localeCompare(b.taxon?.name ?? b.names[0]?.name ?? '');
};

/** A run of nodes shown on one line: taxa without names of the work and with one child are joined to the next. */
function chainFrom(node: TreeNode): TreeNode[] {
  const chain = [node];
  let last = node;
  while (!last.names.length && last.children.length === 1) {
    last = last.children[0];
    chain.push(last);
  }
  return chain;
}

/** The mark of a main subject of the work: an asterisk, read out as words. */
export function MainSubjectMark() {
  return (
    <>
      <span className={classes.mainMark} aria-hidden title="Main subject">*</span>
      <span className={list.srOnly}> (main subject)</span>
    </>
  );
}

/** The legend of the mark, under a list that uses it. */
export function MainSubjectLegend() {
  return <p className={classes.treeLegend}><span aria-hidden>* </span>Main subject of the paper</p>;
}

function Found({ n }: { n: NameDetail }) {
  return (
    <span className={classes.treeName}>
      <SciName name={n.name} rank={n.rank} usageRank={n.usageRank} kind={n.kind} to={`/name/${n.id}`} acts={n.actTypes} />
      {n.mainSubject ? <MainSubjectMark /> : null}
      {n.placementMethod === 'VERBATIM_CONTEXT' ? <span className={classes.note}> (by the verbatim classification)</span> : null}
    </span>
  );
}

function NodeLabel({ node }: { node: TreeNode }) {
  if (node.names.length) {
    return <>{node.names.map((n, i) => <span key={n.id}>{i > 0 ? ', ' : null}<Found n={n} /></span>)}</>;
  }
  const t = node.taxon!;
  return (
    <span className={classes.treeTaxon}>
      <SciName name={t.name} rank={t.rank} to={t.id ? `/taxon/${t.id}` : undefined} />
    </span>
  );
}

function Branch({ nodes }: { nodes: TreeNode[] }) {
  return (
    <ul className={classes.tree}>
      {[...nodes].sort(order).map((node) => {
        const chain = chainFrom(node);
        const last = chain[chain.length - 1];
        return (
          <li key={node.key}>
            <span className={classes.treeRow}>
              {chain.map((c, i) => (
                <span key={c.key}>{i > 0 ? <span className={classes.treeTaxon} aria-hidden> › </span> : null}<NodeLabel node={c} /></span>
              ))}
            </span>
            {last.children.length ? <Branch nodes={last.children} /> : null}
          </li>
        );
      })}
    </ul>
  );
}

/** The work's placed names in one COL tree: the shared root on one line, then the branches indented. */
export function NameTree({ names }: { names: NameDetail[] }) {
  const root = buildNameTree(names);
  if (!root.children.length) return null;
  return (
    <div data-testid="name-tree">
      <Branch nodes={root.children} />
      {names.some((n) => n.mainSubject) ? <MainSubjectLegend /> : null}
    </div>
  );
}
