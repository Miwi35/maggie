import { useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { useCreatePath, useDelete, useListContext, useNotify } from 'react-admin'
import type { RaRecord } from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import Link from '@mui/material/Link'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { isCushionAccount } from './accountCushion'
import { ACCOUNT_TYPE_LABELS } from './accountTypes'
import { Amount } from './AmountField'
import { syncedLabel } from './dashboardHeader'
import { useLastBankSync } from './useLastBankSync'

const ACCOUNT_TOPICS = ['/api/accounts/{id}']

const ACCOUNT_LIVE: LiveConfig = {
  topics: ACCOUNT_TOPICS,
  required: ['name', 'type', 'balanceCents'],
}

const bareId = (id: unknown) => String(id).split('/').pop()

const bankAndType = (account: RaRecord) =>
  [account.bank as string | undefined, ACCOUNT_TYPE_LABELS[account.type as string] ?? (account.type as string)]
    .filter(Boolean)
    .join(' · ')

/** The kebab that opens a small menu; the items close it by themselves. */
const KebabMenu = ({
  label,
  children,
}: {
  label: string
  children: (close: () => void) => React.ReactNode
}) => {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const close = () => setAnchor(null)

  return (
    <>
      <IconButton
        size="small"
        aria-label={label}
        aria-haspopup="menu"
        onClick={(event) => setAnchor(event.currentTarget)}
        sx={{ position: 'relative', zIndex: 1 }}
      >
        <MoreVertIcon fontSize="small" />
      </IconButton>
      <Menu anchorEl={anchor} open={anchor !== null} onClose={close}>
        {children(close)}
      </Menu>
    </>
  )
}

const AccountCard = ({ account, onDelete }: { account: RaRecord; onDelete: (account: RaRecord) => void }) => {
  const createPath = useCreatePath()
  const name = account.name as string

  return (
    <Card
      data-testid="account-card"
      sx={{ position: 'relative', transition: 'box-shadow 120ms', '&:hover': { boxShadow: 3 } }}
    >
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="subtitle1" component="h2" sx={{ fontWeight: 600 }}>
              <Link
                component={RouterLink}
                to={`/accounts/${bareId(account.id)}/transactions`}
                color="inherit"
                underline="none"
                sx={{
                  '&::after': { content: '""', position: 'absolute', inset: 0 },
                  '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main' },
                }}
              >
                {name}
              </Link>
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {bankAndType(account)}
            </Typography>
          </Box>
          <KebabMenu label={`Actions de ${name}`}>
            {(close) => [
              <MenuItem
                key="edit"
                component={RouterLink}
                to={createPath({ resource: 'accounts', type: 'edit', id: account.id })}
                onClick={close}
              >
                Modifier
              </MenuItem>,
              <MenuItem
                key="delete"
                onClick={() => {
                  close()
                  onDelete(account)
                }}
              >
                Supprimer
              </MenuItem>,
            ]}
          </KebabMenu>
        </Stack>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mt: 2 }}>
          <Box>{isCushionAccount(account) && <Chip size="small" color="primary" variant="outlined" label="Matelas" />}</Box>
          <Typography variant="h6" component="p">
            <Amount cents={account.balanceCents as number} currency={account.currency as string} bold />
          </Typography>
        </Stack>
      </CardContent>
    </Card>
  )
}

const LastImportCard = () => {
  const syncedAt = useLastBankSync()

  return (
    <Card data-testid="last-import-card">
      <CardContent>
        <Typography variant="subtitle1" component="h2" sx={{ fontWeight: 600 }}>
          Dernier import
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {syncedAt ? syncedLabel(syncedAt, new Date()) : 'Jamais'}
        </Typography>
      </CardContent>
    </Card>
  )
}

const DeleteConfirmation = ({ account, onClose }: { account: RaRecord | null; onClose: () => void }) => {
  const notify = useNotify()
  const [deleteOne] = useDelete()

  const confirm = () => {
    if (!account) return
    const name = account.name as string
    onClose()
    deleteOne(
      'accounts',
      { id: account.id, previousData: account },
      { onError: () => notify(`Erreur : le compte « ${name} » n'a pas été supprimé`, { type: 'error' }) },
    )
  }

  return (
    <Dialog open={account !== null} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Supprimer le compte « {String(account?.name ?? '')} » ?</DialogTitle>
      <DialogActions>
        <Button onClick={onClose}>Annuler</Button>
        <Button color="error" variant="contained" onClick={confirm}>
          Confirmer
        </Button>
      </DialogActions>
    </Dialog>
  )
}

const AccountsPage = () => {
  const { data, isPending } = useListContext()
  const [deleting, setDeleting] = useState<RaRecord | null>(null)

  if (isPending) return null

  const accounts = data ?? []

  return (
    <Box sx={{ py: 1 }}>
      <Stack direction="row" justifyContent="flex-end" alignItems="center" spacing={1} sx={{ mb: 2 }}>
        <Button component={RouterLink} to="/accounts/create" variant="contained" startIcon={<AddIcon />}>
          Ajouter un compte
        </Button>
        <KebabMenu label="Plus d'actions">
          {(close) => [
            <MenuItem key="import" component={RouterLink} to="/finance/import" onClick={close}>
              Import de relevés
            </MenuItem>,
            <MenuItem key="contacts" component={RouterLink} to="/contacts" onClick={close}>
              Contacts
            </MenuItem>,
          ]}
        </KebabMenu>
      </Stack>
      {accounts.length === 0 ? (
        <Typography variant="h6" sx={{ textAlign: 'center', py: 6 }}>
          Aucun compte pour l'instant
        </Typography>
      ) : (
        <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 2 }}>
          {accounts.map((account) => (
            <AccountCard key={String(account.id)} account={account} onDelete={setDeleting} />
          ))}
          <LastImportCard />
        </Box>
      )}
      <DeleteConfirmation account={deleting} onClose={() => setDeleting(null)} />
    </Box>
  )
}

export const AccountList = () => (
  <LiveList live={ACCOUNT_LIVE} actions={false} empty={false} pagination={false} perPage={100}>
    <AccountsPage />
  </LiveList>
)
