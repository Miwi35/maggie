import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { accountLabel } from './accountLabel'
import { formatCents } from './accountTypes'
import { DashboardTile } from './DashboardTile'
import { TodoPill } from './TodoPill'
import type { AccountsTile as AccountsTileData, DashboardTodo } from './useFinanceDashboard'

// Example until the « Créances, dettes et contacts » lot wires the real figure.
const OWED_EXAMPLE = 'On te doit 535 €'

/** The account the « rejets » pill opens: the one with the most rejections to settle. */
const rejectionsAccount = (tile: AccountsTileData) =>
  tile.accounts.reduce<AccountsTileData['accounts'][number] | null>(
    (best, account) =>
      account.rejectionsToSettleCount > (best?.rejectionsToSettleCount ?? 0) ? account : best,
    null,
  )

/** « Comptes » → the accounts list: the total, the part set aside, each account, what waits to be sorted or settled. */
export const AccountsTile = ({ tile, todo }: { tile: AccountsTileData; todo: DashboardTodo }) => {
  const rejections = rejectionsAccount(tile)

  return (
    <DashboardTile title="Comptes" to="/accounts" testId="tile-accounts">
      <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 1, mb: 1 }} data-testid="accounts-pills">
        {todo.toSortCount > 0 && (
          <TodoPill label={`${todo.toSortCount} à trier`} to="/finance/to-sort" testId="pill-to-sort" />
        )}
        {todo.rejectionsToSettleCount > 0 && (
          <TodoPill
            label={`${todo.rejectionsToSettleCount} ${todo.rejectionsToSettleCount > 1 ? 'rejets' : 'rejet'}`}
            to={rejections ? `/accounts/${rejections.id}/transactions?tab=incidents` : '/accounts'}
            testId="pill-rejections"
          />
        )}
        <TodoPill label={OWED_EXAMPLE} testId="pill-owed-example" />
      </Stack>
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
}
