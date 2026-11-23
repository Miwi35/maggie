import { useLocation } from 'react-router-dom'
import { BILAN_ACTIVITY_PATH } from '../../modules/maggie/bilan/bilanStatusApi'
import { bilanMoment } from '../../modules/maggie/bilan/bilanBlock'
import type { ActivityLaunchState } from '../activity/activityOrigin'
import { MomentCard } from './MomentCard'

/** The card under the evening review's message: « Ouvrir » opens the activity, « Quitter » comes back here. */
export const BilanMomentCard = ({ messageId, day }: { messageId: string; day: string }) => {
  const location = useLocation()
  const state: ActivityLaunchState = { from: `${location.pathname}${location.search}` }

  return (
    <MomentCard
      messageId={messageId}
      icon="moon"
      title="Bilan de la journée"
      moment={bilanMoment(day)}
      to={BILAN_ACTIVITY_PATH}
      state={state}
      openLabel="Ouvrir"
      ariaLabel="Ouvrir le bilan de la journée"
    />
  )
}
