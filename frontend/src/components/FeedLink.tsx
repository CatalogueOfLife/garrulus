import classes from '../ui/detail.module.css';

/** A plain underlined link to a public Atom feed. */
export function FeedLink({ href }: { href: string }) {
  return <a href={href} className={classes.feed}>Atom feed</a>;
}
