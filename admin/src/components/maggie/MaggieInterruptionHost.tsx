import { MaggieInterruption } from './MaggieInterruption'
import { useMaggieInterruption } from './useMaggieInterruption'

interface MaggieInterruptionHostProps {
  chatOpen: boolean
  onOpenChat: () => void
}

export const MaggieInterruptionHost = ({ chatOpen, onOpenChat }: MaggieInterruptionHostProps) => {
  const { current, dismiss, openChat } = useMaggieInterruption({ chatOpen, onOpenChat })

  return (
    <MaggieInterruption
      open={current !== null}
      message={current?.message ?? ''}
      actionLabel="Ouvrir le chat"
      onAction={openChat}
      onLater={() => dismiss(true)}
    />
  )
}
