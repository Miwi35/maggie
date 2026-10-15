import Chip from '@mui/material/Chip'

/**
 * « de saison » / « hors saison » (MAG-494), from the `inSeason` the API
 * computes for this month. Null or absent — a product without a season, a
 * free-text line — shows nothing.
 */
export const SeasonBadge = ({ inSeason }: { inSeason?: boolean | null }) => {
  if (inSeason == null) return null

  return (
    <Chip
      label={inSeason ? 'de saison' : 'hors saison'}
      color={inSeason ? 'success' : 'default'}
      size="small"
      variant="outlined"
      data-testid="season-badge"
      sx={{ flexShrink: 0, mr: 1 }}
    />
  )
}
