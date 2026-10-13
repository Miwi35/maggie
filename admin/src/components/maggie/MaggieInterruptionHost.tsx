import { useCallback, useEffect, useRef, useState } from 'react'
import { useDataProvider } from 'react-admin'
import { useNavigate } from 'react-router-dom'
import { MaggieInterruption } from './MaggieInterruption'
import { useMaggieInterruption } from './useMaggieInterruption'

interface MaggieInterruptionHostProps {
  chatOpen: boolean
  onOpenChat: () => void
}

/** The agent no longer holds this question (answered elsewhere, expired, gone): nothing left to ask. */
const GONE = [404, 409, 410]

async function answerApproval(id: string, decision: 'approve' | 'deny'): Promise<boolean> {
  const token = localStorage.getItem('token')
  const response = await fetch(`/agent/approvals/${encodeURIComponent(id)}/${decision}`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  return response.ok || GONE.includes(response.status)
}

export const MaggieInterruptionHost = ({ chatOpen, onOpenChat }: MaggieInterruptionHostProps) => {
  const { current, dismiss, openChat } = useMaggieInterruption({ chatOpen, onOpenChat })
  const dataProvider = useDataProvider()
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // A new interruption starts clean, whatever became of the one before.
  const currentId = current?.id
  const currentIdRef = useRef(currentId)
  useEffect(() => {
    currentIdRef.current = currentId
  }, [currentId])
  const [shownId, setShownId] = useState(currentId)
  if (shownId !== currentId) {
    setShownId(currentId)
    setBusy(false)
    setError(null)
  }

  const answer = useCallback(
    async (decision: 'approve' | 'deny') => {
      if (current?.source !== 'approval') return
      setBusy(true)
      setError(null)
      const failed = () => {
        // Overtaken meanwhile (answered elsewhere): the question now shown is not the one that failed.
        if (currentIdRef.current !== current.id) return
        setError("Ta réponse n'est pas partie. Réessaie, ou garde-la pour plus tard.")
        setBusy(false)
      }
      try {
        if (await answerApproval(current.ref, decision)) dismiss(false)
        else failed()
      } catch {
        failed()
      }
    },
    [current, dismiss],
  )

  // As from the bell: read, so it leaves the bell and the other devices. Leaving it for later keeps it unread.
  const markRead = useCallback(() => {
    if (current?.source !== 'notification') return
    dataProvider
      .update('notifications', {
        id: current.ref,
        data: { readAt: new Date().toISOString() },
        previousData: { id: current.ref },
      })
      .catch(() => {
        // The bell still lists it as unread: nothing worth stopping for.
      })
    dismiss(false)
  }, [current, dataProvider, dismiss])

  const act = useCallback(() => {
    if (!current) return
    if (current.source === 'proaction') return openChat()
    if (current.source === 'approval') return void answer('approve')
    markRead()
  }, [answer, current, markRead, openChat])

  const openLink = useCallback(() => {
    if (current?.source === 'approval') return void answer('deny')
    if (current?.source !== 'notification' || !current.link) return
    markRead()
    navigate(current.link.path)
  }, [answer, current, markRead, navigate])

  const link = current?.source === 'notification' ? current.link : null
  const actionLabel =
    current?.source === 'approval'
      ? 'Autoriser'
      : current?.source === 'notification'
        ? link
          ? "C'est noté"
          : 'Compris'
        : 'Ouvrir le chat'
  const secondaryLabel = current?.source === 'approval' ? 'Refuser' : link?.label

  return (
    <MaggieInterruption
      open={current !== null}
      id={current?.id}
      title={current?.title}
      message={current?.message ?? ''}
      actionLabel={actionLabel}
      onAction={act}
      secondaryLabel={secondaryLabel}
      onSecondary={openLink}
      holdsQuestion={current?.source === 'approval'}
      busy={busy}
      error={error}
      onLater={() => dismiss(true)}
    />
  )
}
