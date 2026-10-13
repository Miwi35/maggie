import Box from '@mui/material/Box'
import AccountBalanceIcon from '@mui/icons-material/AccountBalance'
import BoltIcon from '@mui/icons-material/Bolt'
import CloudIcon from '@mui/icons-material/Cloud'
import DirectionsCarIcon from '@mui/icons-material/DirectionsCar'
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline'
import FavoriteIcon from '@mui/icons-material/Favorite'
import FitnessCenterIcon from '@mui/icons-material/FitnessCenter'
import FlightIcon from '@mui/icons-material/Flight'
import HomeIcon from '@mui/icons-material/Home'
import LabelIcon from '@mui/icons-material/Label'
import LocalGasStationIcon from '@mui/icons-material/LocalGasStation'
import MovieIcon from '@mui/icons-material/Movie'
import PaymentsIcon from '@mui/icons-material/Payments'
import RedeemIcon from '@mui/icons-material/Redeem'
import RepeatIcon from '@mui/icons-material/Repeat'
import RestaurantIcon from '@mui/icons-material/Restaurant'
import SavingsIcon from '@mui/icons-material/Savings'
import SchoolIcon from '@mui/icons-material/School'
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart'
import SportsEsportsIcon from '@mui/icons-material/SportsEsports'
import StarIcon from '@mui/icons-material/Star'
import TrendingUpIcon from '@mui/icons-material/TrendingUp'
import TvIcon from '@mui/icons-material/Tv'
import UndoIcon from '@mui/icons-material/Undo'
import VolunteerActivismIcon from '@mui/icons-material/VolunteerActivism'
import WaterDropIcon from '@mui/icons-material/WaterDrop'
import WifiIcon from '@mui/icons-material/Wifi'

/** The Material icon behind each name a category's `icon` field takes (« shopping-cart »). */
export const CATEGORY_ICONS: Record<string, typeof HomeIcon> = {
  home: HomeIcon,
  'shopping-cart': ShoppingCartIcon,
  repeat: RepeatIcon,
  'water-drop': WaterDropIcon,
  bolt: BoltIcon,
  wifi: WifiIcon,
  tv: TvIcon,
  cloud: CloudIcon,
  'sports-esports': SportsEsportsIcon,
  'trending-up': TrendingUpIcon,
  'local-gas-station': LocalGasStationIcon,
  'account-balance': AccountBalanceIcon,
  'error-outline': ErrorOutlineIcon,
  payments: PaymentsIcon,
  star: StarIcon,
  'volunteer-activism': VolunteerActivismIcon,
  undo: UndoIcon,
  redeem: RedeemIcon,
  restaurant: RestaurantIcon,
  'directions-car': DirectionsCarIcon,
  flight: FlightIcon,
  school: SchoolIcon,
  favorite: FavoriteIcon,
  'fitness-center': FitnessCenterIcon,
  movie: MovieIcon,
  savings: SavingsIcon,
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
 * A name we do not draw still shows as a generic tag, so an icon that was set is never
 * silently absent; no icon at all leaves the plain colour.
 */
export const CategoryGlyph = ({ color, icon }: CategoryGlyphProps) => {
  const fill = typeof color === 'string' && HEX.test(color) ? color.toUpperCase() : null
  const name = typeof icon === 'string' ? icon.trim().toLowerCase() : ''
  const Icon = name === '' ? null : (CATEGORY_ICONS[name] ?? LabelIcon)
  const description = [fill && `couleur ${fill}`, name !== '' && `icône ${icon?.trim()}`].filter(Boolean).join(', ')

  return (
    <Box
      component="span"
      role="img"
      aria-label={`Repère : ${description || 'sans couleur ni icône'}`}
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
      {Icon && <Icon sx={{ fontSize: 18 }} />}
    </Box>
  )
}
