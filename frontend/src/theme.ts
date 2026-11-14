import { createTheme, type MantineColorsTuple } from '@mantine/core';

const jay: MantineColorsTuple = ['#EAF3FC', '#D3E6F8', '#A8CEF2', '#7AB4EA', '#5199E0', '#3482D3', '#2A6FBA', '#235C9B', '#1C4A7C', '#14365B'];
const fawn: MantineColorsTuple = ['#FAF4F1', '#F2E5DF', '#E6CDC2', '#D8B3A4', '#CB9C8A', '#BC8672', '#A0705D', '#82594A', '#644337', '#462E25'];
const ink: MantineColorsTuple = ['#C9C5C2', '#ADA8A4', '#8D8783', '#6B6662', '#4B4744', '#383533', '#2A2826', '#201E1D', '#181716', '#100F0F'];

export const theme = createTheme({
  primaryColor: 'jay',
  primaryShade: { light: 6, dark: 6 },
  colors: { jay, fawn, dark: ink },
  black: '#100F0F',
  defaultRadius: 'md',
  headings: { fontFamily: "Georgia, 'Times New Roman', serif" },
});
