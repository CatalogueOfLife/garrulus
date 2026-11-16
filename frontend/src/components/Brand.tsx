import { Group, Image, Title } from '@mantine/core';
import { Link } from 'react-router-dom';
import mark from '../assets/garrulus-mark.svg';
import markDark from '../assets/garrulus-mark-dark.svg';

export function Brand() {
  return (
    <Link to="/" aria-label="Garrulus home" style={{ textDecoration: 'none', color: 'inherit' }}>
      <Group gap="xs" wrap="nowrap">
        <Image src={mark} alt="" w={32} h={32} lightHidden={false} darkHidden />
        <Image src={markDark} alt="" w={32} h={32} lightHidden darkHidden={false} />
        <Title order={1} size="h3" ff="Georgia, 'Times New Roman', serif" fs="italic" fw={600}>
          Garrulus
        </Title>
      </Group>
    </Link>
  );
}
