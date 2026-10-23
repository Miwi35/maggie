import { useState } from 'react'
import Button from '@mui/material/Button'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { currentLabel } from './scenariosApi'
import type { Scenario } from './scenariosApi'

interface Props {
  current: Scenario | null
  scenarios: Scenario[]
  busy: boolean
  onMakeCurrent: (scenario: Scenario) => void
  onClear: () => void
}

/** « Scénario courant · Rapide · N : mars 2041 », or « Aucun scénario courant » with the means to pick one. */
export const ScenarioCurrentBanner = ({ current, scenarios, busy, onMakeCurrent, onClear }: Props) => {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)

  return (
    <Stack direction="row" data-testid="scenario-current" sx={{ alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
      {current ? (
        <>
          <Typography sx={{ fontWeight: 600 }}>{currentLabel(current)}</Typography>
          <Button size="small" color="inherit" disabled={busy} onClick={onClear}>
            Retirer le courant
          </Button>
        </>
      ) : (
        <>
          <Typography sx={{ fontWeight: 600 }}>Aucun scénario courant</Typography>
          <Button size="small" variant="outlined" disabled={busy || scenarios.length === 0} onClick={(event) => setAnchor(event.currentTarget)}>
            Choisir le courant
          </Button>
          <Menu anchorEl={anchor} open={anchor !== null} onClose={() => setAnchor(null)}>
            {scenarios.map((scenario) => (
              <MenuItem
                key={scenario.id}
                onClick={() => {
                  setAnchor(null)
                  onMakeCurrent(scenario)
                }}
              >
                {scenario.name}
              </MenuItem>
            ))}
          </Menu>
        </>
      )}
    </Stack>
  )
}
