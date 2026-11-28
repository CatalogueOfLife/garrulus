import { Link } from 'react-router-dom';
import { Rule } from './Rule';
import styles from './frame.module.css';

const REPO = 'https://github.com/CatalogueOfLife/garrulus';
// typeof, not the bare name: a Vite config without the define (a scratch dev config) must not break the page
const BUILT_FROM = typeof __APP_COMMIT__ === 'string' ? __APP_COMMIT__ : '';

/** The commit the page was built from, as a short hash linking to it; nothing when the build did not know it. */
export function Version({ commit = BUILT_FROM }: { commit?: string }) {
  if (!/^[0-9a-f]{7,40}$/i.test(commit)) return null;
  return <a href={`${REPO}/commit/${commit}`} title={`Built from commit ${commit}`}>Version {commit.slice(0, 7)}</a>;
}

export function Footer() {
  return (
    <footer className={styles.footer}>
      <Rule />
      <div className={styles.footerLine}>
        <a href="https://www.catalogueoflife.org">Garrulus, part of the Catalogue of Life</a>
        <Link to="/about">About</Link>
        <a href="/api/openapi.yaml">API</a>
        <Version />
      </div>
    </footer>
  );
}
