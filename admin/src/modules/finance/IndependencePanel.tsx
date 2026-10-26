import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import { Title } from 'react-admin'
import { Link } from 'react-router-dom'
import { Placeholder } from '../../components/list/ListEmpty'
import { IndependenceProjectionBlock } from './IndependenceProjection'
import { IndependenceSummary } from './IndependenceSummary'
import { TimelinePanel } from './TimelinePanel'
import { useFinanceDashboard } from './useFinanceDashboard'

/**
 * The Indépendance aspect of Planification (MAG-518): the counter of the
 * dashboard's Planification tile, as a page of its own, with the date N, the
 * coverage curve and the dated milestones (MAG-549). Without a rente
 * declared it offers to declare one instead of showing an empty counter.
 * Below it, the 20-year frise (MAG-554, MAG-633).
 */
export const IndependencePanel = () => {
  const now = new Date()
  const { dashboard, loading, error, refresh } = useFinanceDashboard(now.getFullYear(), now.getMonth() + 1)

  return (
    <>
      <Title title="Indépendance financière" />

      {error && !dashboard && (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => { refresh() }}>
              Réessayer
            </Button>
          }
        >
          Le compteur d'indépendance n'a pas pu être chargé.
        </Alert>
      )}

      {loading && !dashboard && <LinearProgress />}

      {dashboard &&
        (dashboard.independence.hasPassiveIncomeCategories ? (
          <>
            <IndependenceSummary counter={dashboard.independence} withDate={false} />
            <IndependenceProjectionBlock counter={dashboard.independence} />
          </>
        ) : (
          <Placeholder
            title="Aucune rente déclarée"
            description="Cochez « Rente » sur une catégorie de recette — loyers perçus, dividendes, intérêts — pour suivre la part du train de vie qu'elles couvrent."
            action={
              <Button component={Link} to="/categories" variant="contained">
                Déclarer une rente
              </Button>
            }
          />
        ))}

      <TimelinePanel />
    </>
  )
}
