import type { SubscriptionType } from '../api';
import { FeedLink } from './FeedLink';
import { FollowButton } from './FollowButton';

/** The page actions of anything one can follow: its public Atom feed and the Follow pill, always in this order. */
export function FollowActions({ type, target, label, feed }: {
  type: SubscriptionType;
  target: string;
  label: string;
  feed: string;
}) {
  return (
    <>
      <FeedLink href={feed} />
      <FollowButton type={type} target={target} label={label} />
    </>
  );
}
