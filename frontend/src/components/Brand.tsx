import { Group, Image, Title } from '@mantine/core';
import mark from '../assets/garrulus-mark.svg';
import markDark from '../assets/garrulus-mark-dark.svg';

export function Brand() {
  return (
    <Group gap="xs" wrap="nowrap">
      <Image src={mark} alt="" w={32} h={32} lightHidden={false} darkHidden />
      <Image src={markDark} alt="" w={32} h={32} lightHidden darkHidden={false} />
      <Title order={1} size="h3" ff="Georgia, 'Times New Roman', serif" fs="italic" fw={600}>
        Garrulus
      </Title>
    </Group>
  );
}
