import {
  Alert,
  Badge,
  Button,
  Checkbox,
  type CSSVariablesResolver,
  Loader,
  Menu,
  Modal,
  NativeSelect,
  Pagination,
  Popover,
  Radio,
  SegmentedControl,
  Select,
  Skeleton,
  TextInput,
  createTheme,
  type MantineColorsTuple,
} from '@mantine/core';
import classes from './theme.module.css';

// Mantine needs real colours in its tuples; every other colour comes from the --gr-* tokens.
const ink = Array(10).fill('#000000') as unknown as MantineColorsTuple;
const red = Array(10).fill('#D2381C') as unknown as MantineColorsTuple;

const fontStack = "'Schibsted Grotesk', 'Helvetica Neue', Arial, sans-serif";

export const theme = createTheme({
  fontFamily: fontStack,
  headings: { fontFamily: fontStack, fontWeight: '500' },
  primaryColor: 'ink',
  colors: { ink, red },
  defaultRadius: 0,
  focusRing: 'never',
  components: {
    Button: Button.extend({ defaultProps: { radius: 'xl', variant: 'default' } }),
    TextInput: TextInput.extend({ defaultProps: { radius: 'xl' } }),
    Select: Select.extend({ defaultProps: { radius: 'xl' } }),
    NativeSelect: NativeSelect.extend({ defaultProps: { radius: 'xl' } }),
    SegmentedControl: SegmentedControl.extend({ defaultProps: { radius: 'xl' } }),
    Pagination: Pagination.extend({
      defaultProps: { radius: 'xl' },
      classNames: { control: classes.paginationControl },
    }),
    Alert: Alert.extend({
      defaultProps: { radius: 0 },
      classNames: (_theme, props) => ({
        root: classes.alertRoot,
        title: props.color === 'red' ? `${classes.alertTitle} ${classes.alertTitleRed}` : classes.alertTitle,
      }),
    }),
    Menu: Menu.extend({ defaultProps: { radius: 0, shadow: 'none' } }),
    Modal: Modal.extend({ defaultProps: { radius: 0, shadow: 'none' } }),
    Popover: Popover.extend({ defaultProps: { radius: 0, shadow: 'none' } }),
    Checkbox: Checkbox.extend({ classNames: { input: classes.checkboxInput, icon: classes.checkboxIcon } }),
    Radio: Radio.extend({ classNames: { radio: classes.checkboxInput, icon: classes.checkboxIcon } }),
    Loader: Loader.extend({ defaultProps: { type: 'dots', color: 'var(--gr-ink)' } }),
    Skeleton: Skeleton.extend({ classNames: { root: classes.skeleton } }),
    Badge: Badge.extend({ defaultProps: { radius: 'xl' }, classNames: { root: classes.badgeRoot } }),
  },
});

const mapped: Record<string, string> = {
  '--mantine-color-body': 'var(--gr-paper)',
  '--mantine-color-text': 'var(--gr-ink)',
  '--mantine-color-dimmed': 'var(--gr-grey)',
  '--mantine-color-default-border': 'var(--gr-ink)',
  '--mantine-color-anchor': 'var(--gr-ink)',
  '--mantine-color-error': 'var(--gr-red)',
  '--mantine-primary-color-filled': 'var(--gr-ink)',
  '--mantine-primary-color-filled-hover': 'var(--gr-ink)',
  '--mantine-primary-color-contrast': 'var(--gr-paper)',
};

// Mantine defines these per colour scheme (higher specificity than :root), so they go in light and dark;
// `variables` repeats them for scheme-independent lookups.
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: { ...mapped },
  light: { ...mapped },
  dark: { ...mapped },
});
