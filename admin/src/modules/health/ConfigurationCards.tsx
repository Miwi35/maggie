import Box from '@mui/material/Box'
import type { ReactNode } from 'react'
import { useId } from 'react'
import { FigureField, SelectField, TimeInput } from './ConfigurationFields'
import {
  ACTIVITY_FACTORS,
  ACTIVITY_LABELS,
  ACTIVITY_LEVELS,
  ENTERED_NEED_MAX,
  ENTERED_NEED_MIN,
  ENTERED_NEED_STEP,
  enteredNeedFrom,
  formatFactor,
  formatInteger,
  formatKcal,
  formatKg,
} from './healthProfileApi'
import type { ActivityLevel, HealthProfile, HealthProfilePatch, NeedMode, Sex, StepSource } from './healthProfileApi'

type Save = (patch: HealthProfilePatch, shown?: Partial<HealthProfile>) => Promise<boolean>

const Card = ({ label, children, sx }: { label: string; children: ReactNode; sx?: object }) => (
  <Box
    component="section"
    aria-label={label}
    sx={{ borderRadius: 4, bgcolor: 'background.paper', px: 2.75, py: 2.5, display: 'flex', flexDirection: 'column', gap: 1.75, minWidth: 0, ...sx }}
  >
    {children}
  </Box>
)

const SEX_OPTIONS: [Sex, string][] = [
  ['male', 'Homme'],
  ['female', 'Femme'],
]

const ACTIVITY_OPTIONS: [ActivityLevel, string][] = ACTIVITY_LEVELS.map((level) => [
  level,
  `${ACTIVITY_LABELS[level]} · ${formatFactor(ACTIVITY_FACTORS[level])}`,
])

const MODE_OPTIONS: [NeedMode, string][] = [
  ['computed', 'Calculé'],
  ['entered', 'Saisi'],
]

/** « Calculé · Saisi », one of them checked. */
const ModePicker = ({ mode, onChange }: { mode: NeedMode; onChange: (mode: NeedMode) => void }) => (
  <Box role="radiogroup" aria-label="Mode" sx={{ display: 'flex', gap: 0.5, p: 0.5, borderRadius: 999, bgcolor: 'action.hover', alignSelf: 'flex-start' }}>
    {MODE_OPTIONS.map(([candidate, label]) => {
      const checked = candidate === mode

      return (
        <Box
          key={candidate}
          component="button"
          type="button"
          role="radio"
          aria-checked={checked}
          onClick={() => {
            if (!checked) onChange(candidate)
          }}
          sx={(theme) => ({
            appearance: 'none',
            border: 0,
            m: 0,
            font: 'inherit',
            cursor: 'pointer',
            height: 34,
            px: 1.75,
            borderRadius: 999,
            fontSize: 13,
            fontWeight: checked ? 500 : 400,
            bgcolor: checked ? theme.palette.veilleuse.ink : 'transparent',
            color: checked ? theme.palette.veilleuse.onInk : theme.palette.text.secondary,
          })}
        >
          {label}
        </Box>
      )
    })}
  </Box>
)

/**
 * The profile the need is computed from (Mifflin-St Jeor), or the need typed.
 * The age is written as a year of birth; a weight typed is a weighing taken now.
 */
export const ProfileCard = ({ profile, save }: { profile: HealthProfile; save: Save }) => {
  const switchMode = (mode: NeedMode) =>
    mode === 'entered'
      ? save({ mode, enteredNeedKcal: enteredNeedFrom(profile.needKcal) })
      : save({ mode })

  return (
    <Card label="Profil">
      <ModePicker mode={profile.mode} onChange={switchMode} />
      {profile.mode === 'computed' ? (
        <>
          <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1.5 }}>
            <SelectField label="Sexe" value={profile.sex} options={SEX_OPTIONS} onChange={(sex) => save({ sex })} />
            <FigureField
              label="Âge"
              value={profile.age}
              format={formatInteger}
              unit="ans"
              onSave={(age) => {
                const birthYear = new Date().getFullYear() - Math.round(age)

                return save({ birthYear }, { birthYear, age: Math.round(age) })
              }}
            />
            <FigureField label="Taille" value={profile.heightCm} format={formatInteger} unit="cm" onSave={(heightCm) => save({ heightCm })} />
            <FigureField label="Poids" value={profile.weightKg} format={formatKg} unit="kg" onSave={(weightKg) => save({ weightKg })} />
          </Box>
          <SelectField
            label="Activité"
            value={profile.activityLevel}
            options={ACTIVITY_OPTIONS}
            onChange={(activityLevel) => save({ activityLevel })}
          />
          <Box component="span" sx={{ fontSize: 12, color: 'veilleuse.caption', fontVariantNumeric: 'tabular-nums' }}>
            Mifflin-St Jeor
          </Box>
        </>
      ) : (
        <FigureField
          label="Besoin du jour"
          value={profile.enteredNeedKcal}
          format={String}
          unit="kcal"
          stepper={{ min: ENTERED_NEED_MIN, max: ENTERED_NEED_MAX, step: ENTERED_NEED_STEP }}
          onSave={(enteredNeedKcal) => save({ enteredNeedKcal })}
        />
      )}
    </Card>
  )
}

const RING_RADIUS = 46
const RING_LENGTH = 2 * Math.PI * RING_RADIUS

/** The day's need, the basal metabolism as the share of the ring, the factor between them in its middle. */
export const NeedCard = ({ profile }: { profile: HealthProfile }) => {
  const { bmrKcal, needKcal, factor } = profile
  const share = bmrKcal !== null && needKcal !== null && needKcal > 0 ? Math.min(1, bmrKcal / needKcal) : 0

  return (
    <Card label="Besoin du jour">
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2.5 }}>
        <Box sx={{ position: 'relative', width: 110, height: 110, flex: 'none' }}>
          <Box
            component="svg"
            width={110}
            height={110}
            viewBox="0 0 110 110"
            role="img"
            aria-label={`Métabolisme de base ${formatKcal(bmrKcal)} sur un besoin de ${formatKcal(needKcal)}`}
            sx={(theme) => ({ display: 'block', '& .track': { stroke: theme.palette.veilleuse.track }, '& .arc': { stroke: theme.palette.module.sport } })}
          >
            <circle className="track" cx="55" cy="55" r={RING_RADIUS} fill="none" strokeWidth="10" />
            {share > 0 && (
              <circle
                data-testid="need-ring-arc"
                className="arc"
                cx="55"
                cy="55"
                r={RING_RADIUS}
                fill="none"
                strokeWidth="10"
                strokeLinecap="round"
                strokeDasharray={`${(share * RING_LENGTH).toFixed(1)} ${RING_LENGTH.toFixed(1)}`}
                transform="rotate(-90 55 55)"
              />
            )}
          </Box>
          {factor !== null && (
            <Box
              component="span"
              sx={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 13, color: 'module.sport', fontVariantNumeric: 'tabular-nums' }}
            >
              {formatFactor(factor)}
            </Box>
          )}
        </Box>
        <Box component="span" sx={{ display: 'flex', flexDirection: 'column', gap: 0.5, fontVariantNumeric: 'tabular-nums' }}>
          <Box component="span" data-testid="need-kcal" sx={{ fontSize: 34, fontWeight: 300, letterSpacing: '-0.03em' }}>
            {formatKcal(needKcal)}
          </Box>
          <Box component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
            besoin du jour
          </Box>
          <Box component="span" data-testid="bmr-kcal" sx={{ fontSize: 13, color: 'veilleuse.caption' }}>
            {`métabolisme de base ${formatKcal(bmrKcal)}`}
          </Box>
        </Box>
      </Box>
    </Card>
  )
}

const SESSION_OPTIONS: [number, string][] = [15, 20, 30].map((minutes) => [minutes, `${minutes} min`])

const SOURCE_OPTIONS: [StepSource, string][] = [
  ['health_connect', 'Health Connect'],
  ['none', 'Aucune'],
]

/** What Maggie's activity suggestions go by: the goal, the window she suggests in, a session's length, where the steps come from. */
export const ActivityCard = ({ profile, save }: { profile: HealthProfile; save: Save }) => {
  const windowLabel = useId()

  return (
    <Card label="Activité" sx={{ maxWidth: 860 }}>
      <Box component="span" sx={{ fontSize: 12, letterSpacing: '0.14em', textTransform: 'uppercase', color: 'veilleuse.caption' }}>
        Activité
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(4, minmax(0, 1fr))' }, gap: 1.5 }}>
        <FigureField label="Objectif de pas" value={profile.stepGoal} format={formatInteger} unit="/ jour" onSave={(stepGoal) => save({ stepGoal })} />
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.75, minWidth: 0 }}>
          <Box component="span" id={windowLabel} sx={{ fontSize: 13, color: 'text.secondary' }}>
            Maggie propose entre
          </Box>
          <Box role="group" aria-labelledby={windowLabel} sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
            <TimeInput
              name="Maggie propose à partir de"
              value={profile.suggestionWindowStart}
              onSave={(suggestionWindowStart) => save({ suggestionWindowStart })}
            />
            <Box component="span" aria-hidden>
              –
            </Box>
            <TimeInput
              name="Maggie propose jusqu’à"
              value={profile.suggestionWindowEnd}
              onSave={(suggestionWindowEnd) => save({ suggestionWindowEnd })}
            />
          </Box>
        </Box>
        <SelectField
          label="Durée d’une session"
          value={profile.sessionMinutes}
          options={SESSION_OPTIONS}
          onChange={(sessionMinutes) => save({ sessionMinutes })}
        />
        <SelectField label="Source des pas" value={profile.stepSource} options={SOURCE_OPTIONS} onChange={(stepSource) => save({ stepSource })} />
      </Box>
    </Card>
  )
}
