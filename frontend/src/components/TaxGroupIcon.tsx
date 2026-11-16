import { Image } from '@mantine/core';
import type { TaxGroup } from '../api';
import classes from './TaxGroupIcon.module.css';

export function TaxGroupIcon({ group, size = 18 }: { group: Pick<TaxGroup, 'name' | 'icon'>; size?: number }) {
  return <Image className={classes.icon} src={group.icon} alt={group.name} w={size} h={size} fit="contain" style={{ flex: '0 0 auto' }} />;
}
