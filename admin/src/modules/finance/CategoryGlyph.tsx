import { createElement } from 'react'
import Box from '@mui/material/Box'
import SvgIcon, { type SvgIconProps } from '@mui/material/SvgIcon'
import LabelIcon from '@mui/icons-material/Label'
import { CATEGORY_ICON_SHAPES } from './categoryIconShapes.generated'

interface CategoryIconProps extends Omit<SvgIconProps, 'children'> {
  /** The value of `Category.icon` (« shopping-cart »). A name we do not draw shows as a generic tag. */
  name: string
}

/** The Material icon behind a name a category's `icon` field takes, drawn from the generated shapes. */
export const CategoryIcon = ({ name, ...props }: CategoryIconProps) => {
  const shape = Object.hasOwn(CATEGORY_ICON_SHAPES, name) ? CATEGORY_ICON_SHAPES[name] : undefined
  if (shape === undefined) {
    return <LabelIcon {...props} />
  }

  return (
    <SvgIcon {...props}>
      {typeof shape === 'string'
        ? <path d={shape} />
        : shape.map(([tag, attributes], i) => createElement(tag, { ...attributes, key: i }))}
    </SvgIcon>
  )
}

const HEX = /^#[0-9a-fA-F]{6}$/

/** White or black, whichever reads on this colour. */
const readableOn = (hex: string): string => {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)

  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.5 ? '#000000' : '#FFFFFF'
}

interface CategoryGlyphProps {
  color?: string | null
  icon?: string | null
}

/**
 * A category's colour and icon on its list row: a disc of its colour holding its icon.
 * Decorative (`aria-hidden`): a label here would become part of the name cell's accessible name.
 * A name we do not draw still shows as a generic tag, so an icon that was set is never
 * silently absent; no icon at all leaves the plain colour.
 */
export const CategoryGlyph = ({ color, icon }: CategoryGlyphProps) => {
  const fill = typeof color === 'string' && HEX.test(color) ? color.toUpperCase() : null
  const name = typeof icon === 'string' ? icon.trim().toLowerCase() : ''

  return (
    <Box
      component="span"
      aria-hidden="true"
      data-testid="category-glyph"
      data-color={fill ?? ''}
      data-icon={name}
      sx={{
        width: 28,
        height: 28,
        mr: 1.5,
        borderRadius: '50%',
        flexShrink: 0,
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        verticalAlign: 'middle',
        bgcolor: fill ?? 'action.hover',
        color: fill ? readableOn(fill) : 'text.secondary',
      }}
    >
      {name !== '' && <CategoryIcon name={name} sx={{ fontSize: 18 }} />}
    </Box>
  )
}
