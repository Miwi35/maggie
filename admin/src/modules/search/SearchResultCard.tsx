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

const ICON_ELEMENTS: Record<string, React.ReactElement> = {
  Event: <EventIcon sx={{ color: 'text.secondary', mt: 0.3 }} />,
  CheckCircle: <CheckCircleIcon sx={{ color: 'text.secondary', mt: 0.3 }} />,
  Restaurant: <RestaurantIcon sx={{ color: 'text.secondary', mt: 0.3 }} />,
  ShoppingCart: <ShoppingCartIcon sx={{ color: 'text.secondary', mt: 0.3 }} />,
  CalendarMonth: <CalendarMonthIcon sx={{ color: 'text.secondary', mt: 0.3 }} />,
  ShoppingBag: <ShoppingBagIcon sx={{ color: 'text.secondary', mt: 0.3 }} />,
  DinnerDining: <DinnerDiningIcon sx={{ color: 'text.secondary', mt: 0.3 }} />,
  Repeat: <RepeatIcon sx={{ color: 'text.secondary', mt: 0.3 }} />,
  Notifications: <NotificationsIcon sx={{ color: 'text.secondary', mt: 0.3 }} />,
  Person: <PersonIcon sx={{ color: 'text.secondary', mt: 0.3 }} />,
}

const DEFAULT_ICON = <SearchIcon sx={{ color: 'text.secondary', mt: 0.3 }} />

function getIconElement(indexName: string): React.ReactElement {
  const config = SEARCH_INDEX_CONFIG[indexName]
  return config ? (ICON_ELEMENTS[config.icon] ?? DEFAULT_ICON) : DEFAULT_ICON
}

export function SearchResultCard({ result }: { result: SearchResult }) {
  const navigate = useNavigate()
  const config = SEARCH_INDEX_CONFIG[result.index]
  const icon = getIconElement(result.index)

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
        {icon}
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
