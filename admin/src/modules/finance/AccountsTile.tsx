import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { accountLabel } from './accountLabel'
import { formatCents } from './accountTypes'
import { DashboardTile } from './DashboardTile'
import type { AccountsTile as AccountsTileData } from './useFinanceDashboard'

/** « Comptes » → the accounts list: the total, the part set aside, each account. */
export const AccountsTile = ({ tile }: { tile: AccountsTileData }) => (
  <DashboardTile title="Comptes" to="/accounts" testId="tile-accounts">
    <Typography variant="h5" sx={{ my: 0.5 }}>
      {formatCents(tile.totalCents)}
    </Typography>
    <Typography variant="body2" color="text.secondary">
      dont {formatCents(tile.cushionCents)} de matelas — {formatCents(tile.availableCents)}{' '}
      disponibles
    </Typography>

    {tile.accounts.length === 0 && (
      <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
        Aucun compte pour l'instant.
      </Typography>
    )}
    {tile.accounts.map((account) => (
      <Stack
        key={account.id}
        data-testid="tile-account"
        direction="row"
        justifyContent="space-between"
        sx={{ mt: 0.5 }}
      >
        <Typography variant="caption" color="text.secondary">
          {accountLabel(account)}
          {account.isCushion && ' · matelas'}
        </Typography>
        <Typography variant="caption">{formatCents(account.balanceCents, account.currency)}</Typography>
      </Stack>
    ))}
  </DashboardTile>
)
