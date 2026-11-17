import { useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { getXr } from '../api';
import { Mark } from '../ui/Mark';
import styles from '../ui/pages.module.css';
import { usePageTitle } from '../usePageTitle';

export function AboutPage() {
  usePageTitle('About');
  const xr = useQuery({ queryKey: ['xr'], queryFn: getXr, staleTime: Infinity });
  const { hash } = useLocation();
  useEffect(() => {
    if (hash) document.getElementById(hash.slice(1))?.scrollIntoView();
  }, [hash]);
  return (
    <div className={styles.prose}>
      <h1 className={styles.pageTitle}>About Garrulus</h1>
      <p className={styles.lede}>
        Garrulus is a service of the Catalogue of Life. It follows the journals of biodiversity science, finds scientific
        names in abstracts and in open-access full text, and organises them on the latest Catalogue of Life eXtended
        Release. You can follow taxa, journals, names and taxonomic groups by email digest or Atom feed. All of it
        feeds a public index of names and the literature that uses them.
      </p>

      <h2>How it works</h2>
      <ul>
        <li>Journals and papers come from OpenAlex and Crossref metadata.</li>
        <li>Full text comes from Pensoft, Europe PMC and PubMed Central where a paper is open.</li>
        <li>Names are found with gnfinder plus Garrulus' own rules, and each name points at an exact span of the text.</li>
        <li>
          Names are matched to the Catalogue of Life eXtended Release
          {xr.data ? <>, currently <b>{xr.data.alias}</b></> : null}.
        </li>
        <li>A name is a main subject of a paper when it matters to the paper; otherwise it is only mentioned.</li>
        <li>Names that occur only in the reference list are left out.</li>
      </ul>

      <h2>Name pages and taxon pages</h2>
      <p id="names">
        A <b>name page</b> is about one name as it is written in papers — for example <i>Paussus olcesii</i>. It lists the
        papers that use this spelling, when it first appeared, and where Catalogue of Life places it. Names that COL does
        not know yet, such as newly described species, have name pages too.
      </p>
      <p id="taxa">
        A <b>taxon page</b> is about a taxon in the Catalogue of Life — for example the family Carabidae. It gathers the
        papers about this taxon and everything below it, whatever name the papers use: its species and genera, synonyms
        and misspellings that COL resolves to it. Following a taxon follows all of that.
      </p>
      <p>
        Full text is kept and shown only for open-licensed papers; for other papers Garrulus keeps only the names it found.
      </p>

      <h2>Links</h2>
      <ul>
        <li><a href="/api/openapi.yaml">API description</a></li>
        <li><a href="https://www.catalogueoflife.org">catalogueoflife.org</a></li>
        <li><a href="https://github.com/CatalogueOfLife/nametracker">Source code</a></li>
      </ul>
      <div className={styles.aboutMark}><Mark size={120} /></div>
    </div>
  );
}
