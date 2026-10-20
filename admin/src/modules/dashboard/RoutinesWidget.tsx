import { Link as RouterLink } from 'react-router-dom'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Checkbox from '@mui/material/Checkbox'
import Link from '@mui/material/Link'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import { momentLabel } from '../calendar/routinesApi'
import { useRoutinesDay } from '../calendar/useRoutinesDay'

/**
 * « Routines » on the home (MAG-512): what is due today by moment, ticked in place.
 * The title opens the « Routines » tab of the tasks.
 */
export const RoutinesWidget = () => {
  const { day, loading, failed, pending, toggle } = useRoutinesDay()

  if (loading) {
    return (
      <Card variant="outlined">
        <CardContent>
          <Typography variant="subtitle2" gutterBottom>
            Routines
          </Typography>
          {[1, 2].map((i) => (
            <Skeleton key={i} height={32} />
          ))}
        </CardContent>
      </Card>
    )
  }

  const moments = day?.moments ?? []
  const due = moments.flatMap((moment) => moment.routines)
  if (!failed && due.length === 0) return null

  const done = due.filter((routine) => routine.checked).length

  return (
    <Card variant="outlined">
      <CardContent sx={{ '&:last-child': { pb: 2 } }}>
        <Typography variant="subtitle2" gutterBottom>
          <Link component={RouterLink} to="/tasks?tab=routines" color="inherit" underline="hover">
            Routines
          </Link>
          {!failed && (
            <Typography component="span" variant="caption" color="text.secondary" sx={{ ml: 1 }}>
              {done} sur {due.length} aujourd&apos;hui
            </Typography>
          )}
        </Typography>
        {failed ? (
          <Typography variant="body2" color="error" role="alert">
            Routines indisponibles
          </Typography>
        ) : (
          moments.map((moment) => (
            <Box key={`${moment.anchor}@${moment.time}`} component="section" aria-label={momentLabel(moment)}>
              <Typography variant="caption" color="text.secondary">
                {momentLabel(moment)}
              </Typography>
              <List dense disablePadding>
                {moment.routines.map((routine) => (
                  <ListItem key={routine.id} disableGutters sx={{ py: 0.25 }}>
                    <Checkbox
                      size="small"
                      checked={routine.checked}
                      disabled={pending.has(routine.id)}
                      onChange={() => toggle(routine)}
                      slotProps={{ input: { 'aria-label': `${routine.title} : fait aujourd'hui` } }}
                      sx={{ p: 0.25, mr: 0.5 }}
                    />
                    <Typography
                      variant="body2"
                      noWrap
                      sx={{
                        textDecoration: routine.checked ? 'line-through' : 'none',
                        color: routine.checked ? 'text.disabled' : 'text.primary',
                      }}
                    >
                      {routine.title}
                    </Typography>
                  </ListItem>
                ))}
              </List>
            </Box>
          ))
        )}
      </CardContent>
    </Card>
  )
}
