import Box from '@mui/material/Box'
import { departureText, stepIcon, type Trip } from './trips'

const stepsText = (trip: Trip) => trip.steps.map((step) => `${stepIcon(step.kind)} ${step.minutes}`).join(' · ')

/** The line under an event of the day view: when to leave, from where, and the legs (« 🚶 4 · 🚗 14 · 🚶 3 »). */
export const TripLine = ({ trip }: { trip: Trip }) => (
  <Box className="trip-line" sx={{ fontSize: 11, lineHeight: 1.3, opacity: 0.92, whiteSpace: 'normal' }}>
    <div className="trip-line__depart" style={{ fontWeight: 600 }}>
      {departureText(trip)}
    </div>
    {trip.steps.length > 0 && (
      <div
        className="trip-line__steps"
        title={trip.steps.flatMap((step) => (step.label ? [step.label] : [])).join(', ') || undefined}
      >
        {stepsText(trip)}
      </div>
    )}
  </Box>
)
