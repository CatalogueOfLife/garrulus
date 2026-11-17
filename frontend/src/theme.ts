import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Checkbox,
  CloseButton,
  Combobox,
  type CSSVariablesResolver,
  Divider,
  Input,
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
  Switch,
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
    Button: Button.extend({ defaultProps: { radius: 'xl', variant: 'default' }, classNames: { root: classes.button } }),
    ActionIcon: ActionIcon.extend({ classNames: { root: classes.button } }),
    CloseButton: CloseButton.extend({ classNames: { root: classes.closeButton } }),
    Input: Input.extend({ classNames: { wrapper: classes.inputWrapper, input: classes.input } }),
    Combobox: Combobox.extend({ classNames: { dropdown: classes.dropdown, option: classes.option } }),
    TextInput: TextInput.extend({ defaultProps: { radius: 'xl' } }),
    Select: Select.extend({ defaultProps: { radius: 'xl' } }),
    NativeSelect: NativeSelect.extend({ defaultProps: { radius: 'xl' } }),
    SegmentedControl: SegmentedControl.extend({
      defaultProps: { radius: 'xl' },
      classNames: {
        root: classes.segRoot, control: classes.segControl, indicator: classes.segIndicator, input: classes.segInput, label: classes.segLabel,
      },
    }),
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
    Menu: Menu.extend({
      defaultProps: { radius: 0, shadow: 'none' },
      classNames: { dropdown: classes.dropdown, item: classes.menuItem, divider: classes.menuDivider },
    }),
    Modal: Modal.extend({ defaultProps: { radius: 0, shadow: 'none' } }),
    Popover: Popover.extend({ defaultProps: { radius: 0, shadow: 'none' }, classNames: { dropdown: classes.dropdown } }),
    Divider: Divider.extend({ classNames: { root: classes.divider } }),
    Switch: Switch.extend({ classNames: { input: classes.switchInput, track: classes.switchTrack, thumb: classes.switchThumb } }),
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
  '--mantine-color-red-filled': 'var(--gr-red)',
  '--mantine-color-red-filled-hover': 'var(--gr-red)',
  '--mantine-color-red-light-color': 'var(--gr-red)',
  '--mantine-color-red-outline': 'var(--gr-red)',
  '--mantine-color-red-text': 'var(--gr-red)',
  // Mantine's red shades (Menu.Item color="red" reads red-6), so dark mode gets the dark red
  ...Object.fromEntries(Array.from({ length: 10 }, (_, i) => [`--mantine-color-red-${i}`, 'var(--gr-red)'])),
  // red is never a fill behind text: no tinted hover or light backgrounds
  '--mantine-color-red-light': 'transparent',
  '--mantine-color-red-light-hover': 'transparent',
  '--mantine-color-red-outline-hover': 'transparent',
  // the default (outline) variant: ink on paper, no grey hover fill (Button inverts on hover in theme.module.css)
  '--mantine-color-default': 'var(--gr-paper)',
  '--mantine-color-default-hover': 'transparent',
  '--mantine-color-default-color': 'var(--gr-ink)',
  '--mantine-color-placeholder': 'var(--gr-grey)',
  '--mantine-color-disabled': 'transparent',
  '--mantine-color-disabled-color': 'var(--gr-grey)',
  '--mantine-color-disabled-border': 'var(--gr-grey)',
  '--combobox-chevron-color': 'var(--gr-ink)',
  // a required field's asterisk is not an error
  '--input-asterisk-color': 'var(--gr-ink)',
};

// Mantine defines these per colour scheme (higher specificity than :root), so they go in light and dark;
// `variables` repeats them for scheme-independent lookups.
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: { ...mapped },
  // gray-5 (light) and dark-3 (dark) are Mantine's disabled and placeholder text: the token grey
  light: { ...mapped, '--mantine-color-gray-5': 'var(--gr-grey)' },
  dark: { ...mapped, '--mantine-color-dark-3': 'var(--gr-grey)' },
});
