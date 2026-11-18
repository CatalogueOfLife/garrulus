import type { ReactNode } from 'react';
import { Button } from '@mantine/core';
import { Link } from 'react-router-dom';
import { ApiError } from '../api';
import { EmptyState } from './EmptyState';

/** Whether a failed request found nothing (404), as opposed to failing. */
export const isNotFound = (e: unknown) => e instanceof ApiError && e.status === 404;

/** React Query's retry: three times as by default, but not for a 404, so a not-found page shows at once. */
export const retryUnlessNotFound = (failures: number, error: unknown) => !isNotFound(error) && failures < 3;

/** A page whose subject does not exist: the empty state as the page title, with the way back to the search. */
export function NotFound({ title, children }: { title: string; children: ReactNode }) {
  return (
    <EmptyState headingLevel={1} title={title} action={<Button component={Link} to="/">Go to the search</Button>}>
      {children}
    </EmptyState>
  );
}
