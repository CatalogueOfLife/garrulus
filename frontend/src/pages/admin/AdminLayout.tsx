import { Loader } from '@mantine/core';
import { NavLink, Outlet } from 'react-router-dom';
import { useMe } from '../../auth/useMe';
import { NotFound } from '../../ui/NotFound';
import classes from './admin.module.css';

/** The guard and sub-menu of the admin pages: anyone but an admin sees the ordinary "not found" page. */
export function AdminLayout() {
  const me = useMe();
  if (me.isPending) return <Loader size="sm" />;
  if (!me.data?.admin) return <NotFound title="Page not found">There is nothing at this address.</NotFound>;
  return (
    <div>
      <nav className={classes.menu} aria-label="Admin">
        <NavLink to="/admin/journals">Journals</NavLink>
        <NavLink to="/admin/layers">Layers</NavLink>
        <NavLink to="/admin/status">Status</NavLink>
      </nav>
      <Outlet />
    </div>
  );
}

/** A 403 from an admin call (a session that lost the role): plain text, never a broken page. */
export const NOT_ADMIN = 'You are not an admin.';
