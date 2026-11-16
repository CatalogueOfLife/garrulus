import { useState, type ReactNode } from 'react';
import { Button, Group, Text, type ButtonProps } from '@mantine/core';

interface Props extends Omit<ButtonProps, 'children' | 'onClick'> {
  children: ReactNode;
  /** The question shown after the first click. */
  question: ReactNode;
  /** The label of the button that does it. */
  confirmLabel: string;
  onConfirm: () => void;
}

/** A button that asks first: the first click shows the question, the confirm button and Keep (which backs out). */
export function ConfirmButton({ children, question, confirmLabel, onConfirm, loading, size, ...button }: Props) {
  const [asking, setAsking] = useState(false);
  if (!asking) {
    return <Button size={size} loading={loading} {...button} onClick={() => setAsking(true)}>{children}</Button>;
  }
  return (
    <Group gap="xs" wrap="wrap">
      <Text size="sm">{question}</Text>
      <Button size={size} {...button} loading={loading} onClick={() => { setAsking(false); onConfirm(); }}>{confirmLabel}</Button>
      <Button size={size} variant="default" onClick={() => setAsking(false)}>Keep</Button>
    </Group>
  );
}
