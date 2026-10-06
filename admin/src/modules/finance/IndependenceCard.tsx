import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { formatCents } from './accountTypes'
import type { IndependenceCounter } from './useFinanceDashboard'

const Hint = ({ children }: { children: ReactNode }) => (
  <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
    {children}
  </Typography>
)

/**
 * How far the rentes already cover the way the owner lives (MAG-46).
 *
 * Both figures are measured over the last complete months, so the card says
 * what it compares rather than showing a bare percentage: a ratio whose terms
 * are hidden cannot be checked, and this one is the module's headline number.
 *
 * No target date and no curve: they are Premium (v1.1), and an empty date
 * would read as a promise. No percentage either when there is nothing to
 * measure — 0 % would be a verdict on somebody who has simply not started.
 */
export const IndependenceCard = ({ counter }: { counter: IndependenceCounter }) => (
  <Card sx={{ flex: '1 1 320px' }} data-testid="independence-card">
    <CardContent>
      <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
        Indépendance financière
      </Typography>

      {!counter.hasPassiveIncomeCategories ? (
        <Hint>
          Aucune catégorie n'est déclarée comme rente. Cochez « Rente » sur une catégorie de
          recette — loyers perçus, dividendes, intérêts — pour suivre ce qu'elles couvrent.
        </Hint>
      ) : !counter.isMeasurable ? (
        <Hint>
          Pas encore de train de vie mesuré : il se calcule sur les {counter.sampleMonths} mois
          complets précédents.
        </Hint>
      ) : (
        <>
          <Typography variant="h5" sx={{ my: 0.5 }}>
            {counter.coveragePercent} %
            <Typography component="span" variant="body2" color="text.secondary">
              {' '}
              du train de vie
            </Typography>
          </Typography>

          <LinearProgress
            variant="determinate"
            value={Math.min(100, counter.coveragePercent)}
            color={counter.isReached ? 'success' : 'primary'}
            sx={{ height: 6, borderRadius: 3, my: 1 }}
          />

          <Typography variant="body2" color="text.secondary">
            {formatCents(counter.passiveIncomeCents)} de rentes sur{' '}
            {formatCents(counter.lifestyleCents)} de train de vie, mesurés sur{' '}
            {counter.sampleMonths} mois
          </Typography>

          {counter.isReached ? (
            <Typography variant="body2" color="success.main" sx={{ mt: 0.5 }}>
              Les rentes couvrent le train de vie.
            </Typography>
          ) : (
            <Typography variant="body2" sx={{ mt: 0.5 }}>
              Il manque {formatCents(counter.gapCents)} par mois
              {null !== counter.nextMilestonePercent && (
                <>
                  {' '}
                  — prochain palier {counter.nextMilestonePercent} %, à{' '}
                  {formatCents(counter.nextMilestoneGapCents ?? 0)} près
                </>
              )}
            </Typography>
          )}

          {counter.loanPaymentsCents > 0 && (
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
              Mensualités de prêt comprises : {counter.coverageWithDebtPercent} % des{' '}
              {formatCents(counter.monthlyNeedCents)} que coûte le mois
            </Typography>
          )}

          {counter.byCategory.map((rente) => (
            <Stack
              key={rente.categoryId}
              // The handle the e2e journey uses (MAG-102), as on the top posts.
              data-testid="rente"
              data-category={rente.categoryName}
              direction="row"
              justifyContent="space-between"
              sx={{ mt: 1 }}
            >
              <Typography variant="caption">{rente.categoryName}</Typography>
              <Typography variant="caption">
                {formatCents(rente.monthlyCents)} / mois
                <Box component="span" sx={{ color: 'text.secondary', ml: 1 }}>
                  {rente.sharePercent} %
                </Box>
              </Typography>
            </Stack>
          ))}
        </>
      )}
    </CardContent>
  </Card>
)
