import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import Divider from '@mui/material/Divider'
import Typography from '@mui/material/Typography'
import { TOKENS } from '../../../design/tokens'
import { weatherLabels } from './digestApi'
import type { Weather } from './digestApi'

const degrees = (value: number | null | undefined): string | null => (value == null ? null : `${Math.round(value)}°`)

/** The Météo card of the digest: the max in big and the min under it, the weather word, its quantity and the alerts, the chance of rain and the wind aligned right. Not clickable, no illustration. */
export const WeatherCard = ({ weather }: { weather: Weather }) => {
  const { condition, quantity } = weatherLabels(weather)
  const max = degrees(weather.maxC)
  const min = degrees(weather.minC)
  const rain = weather.rainProbability == null ? null : `pluie ${Math.round(weather.rainProbability)} %`
  const wind = weather.windKmh == null ? null : `vent ${Math.round(weather.windKmh)} km/h`

  return (
    <Card component="section" aria-label="Météo" data-testid="digest-weather" sx={{ p: 2 }}>
      <Typography variant="subtitle1" component="h2" sx={(theme) => ({ fontWeight: TOKENS.typography.weight.semibold, mb: 1, color: theme.palette.info.main })}>
        ● Météo
      </Typography>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
        <Box>
          {max && (
            <Typography data-testid="digest-weather-max" sx={{ fontSize: 40, lineHeight: 1.1, fontWeight: 300 }}>
              {max}
            </Typography>
          )}
          {min && (
            <Typography variant="body2" color="text.secondary">
              min {min}
            </Typography>
          )}
        </Box>
        <Divider orientation="vertical" flexItem />
        <Box sx={{ flex: 1, minWidth: 0 }}>
          {condition && (
            <Typography variant="subtitle1" sx={{ fontWeight: TOKENS.typography.weight.semibold }}>
              {condition}
            </Typography>
          )}
          {quantity && (
            <Typography variant="body2" color="text.secondary">
              {quantity}
            </Typography>
          )}
          {(weather.alerts ?? []).map((alert) => (
            <Typography key={alert} variant="body2" role="status" sx={(theme) => ({ color: theme.palette.warning.main, fontWeight: 600 })}>
              {alert}
            </Typography>
          ))}
        </Box>
        <Box sx={{ textAlign: 'right' }}>
          {rain && (
            <Typography variant="body2" color="text.secondary">
              {rain}
            </Typography>
          )}
          {wind && (
            <Typography variant="body2" color="text.secondary">
              {wind}
            </Typography>
          )}
        </Box>
      </Box>
    </Card>
  )
}
