import { Link } from 'react-router-dom';
import { Rule } from './Rule';
import styles from './frame.module.css';

export function Footer() {
  return (
    <footer className={styles.footer}>
      <Rule />
      <div className={styles.footerLine}>
        <a href="https://www.catalogueoflife.org">Garrulus, part of the Catalogue of Life</a>
        <Link to="/about">About</Link>
        <a href="/api/openapi.yaml">API</a>
      </div>
    </footer>
  );
}
