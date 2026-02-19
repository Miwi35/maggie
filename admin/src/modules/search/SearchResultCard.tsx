import { useNavigate } from 'react-router-dom'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import Paper from '@mui/material/Paper'
import Typography from '@mui/material/Typography'
import EventIcon from '@mui/icons-material/Event'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import RestaurantIcon from '@mui/icons-material/Restaurant'
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart'
import CalendarMonthIcon from '@mui/icons-material/CalendarMonth'
import ShoppingBagIcon from '@mui/icons-material/ShoppingBag'
import DinnerDiningIcon from '@mui/icons-material/DinnerDining'
import RepeatIcon from '@mui/icons-material/Repeat'
import NotificationsIcon from '@mui/icons-material/Notifications'
import PersonIcon from '@mui/icons-material/Person'
import SearchIcon from '@mui/icons-material/Search'
import { SEARCH_INDEX_CONFIG, getResultLabel, getResultHighlight, getResultPath } from './searchConfig'
import type { SearchResult } from './searchConfig'

const ICON_COMPONENTS: Record<string, React.ElementType> = {
  Event: EventIcon,
  CheckCircle: CheckCircleIcon,
  Restaurant: RestaurantIcon,
  ShoppingCart: ShoppingCartIcon,
  CalendarMonth: CalendarMonthIcon,
  ShoppingBag: ShoppingBagIcon,
  DinnerDining: DinnerDiningIcon,
  Repeat: RepeatIcon,
  Notifications: NotificationsIcon,
  Person: PersonIcon,
}

function getIconComponent(indexName: string): React.ElementType {
  const config = SEARCH_INDEX_CONFIG[indexName]
  return config ? (ICON_COMPONENTS[config.icon] ?? SearchIcon) : SearchIcon
}

export function SearchResultCard({ result }: { result: SearchResult }) {
  const navigate = useNavigate()
  const config = SEARCH_INDEX_CONFIG[result.index]
  const Icon = getIconComponent(result.index)

  const handleClick = () => {
    navigate(getResultPath(result))
  }

  return (
    <Paper
      sx={{
        p: 2,
        cursor: 'pointer',
        '&:hover': { bgcolor: 'action.hover' },
        transition: 'background-color 0.15s',
      }}
      onClick={handleClick}
      variant="outlined"
    >
      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.5 }}>
        <Icon sx={{ color: 'text.secondary', mt: 0.3 }} />
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
            <Typography variant="subtitle1" sx={{ fontWeight: 500 }} noWrap>
              {getResultLabel(result)}
            </Typography>
            <Chip
              label={config?.label ?? result.index}
              size="small"
              variant="outlined"
              sx={{ flexShrink: 0 }}
            />
          </Box>
          <Typography
            variant="body2"
            color="text.secondary"
            sx={{ '& em': { fontStyle: 'normal', fontWeight: 700 } }}
            dangerouslySetInnerHTML={{ __html: getResultHighlight(result) }}
          />
        </Box>
      </Box>
    </Paper>
  )
}
