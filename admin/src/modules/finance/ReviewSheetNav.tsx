import { useEffect } from 'react'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import type { Theme } from '@mui/material/styles'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import type { SequenceCursor } from './reviewSequence'

/** A wide window shows the list and the fiche side by side; a narrow one shows one of them. */
export const useWideReview = (): boolean => useMediaQuery((theme: Theme) => theme.breakpoints.up('md'))

/** Keys typed into a field, or aimed at an open dialog or menu, are not the fiche's to take. */
const isForAnotherControl = (target: EventTarget | null): boolean =>
  target instanceof HTMLElement &&
  (target.isContentEditable ||
    target.closest('input, textarea, select, [role="dialog"], [role="listbox"], [role="menu"], [role="slider"]') !== null)

/** ← and → go to the previous and the next line of the list while a fiche is on screen. */
export function useArrowNavigation(cursor: Pick<SequenceCursor, 'isOpen' | 'previous' | 'next'>) {
  const { isOpen, previous, next } = cursor

  useEffect(() => {
    if (!isOpen) return undefined
    const onKey = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || isForAnotherControl(event.target)) return
      if (event.key === 'ArrowLeft') {
        event.preventDefault()
        previous()
      } else if (event.key === 'ArrowRight') {
        event.preventDefault()
        next()
      }
    }
    document.addEventListener('keydown', onKey)

    return () => document.removeEventListener('keydown', onKey)
  }, [isOpen, previous, next])
}

/** « 3 / 12 », Précédent, Suivant — and « Retour à la liste » when the fiche replaces the list. */
export const ReviewSheetNav = ({
  cursor,
  showBack,
}: {
  cursor: Pick<SequenceCursor, 'position' | 'total' | 'hasPrevious' | 'hasNext' | 'previous' | 'next' | 'close'>
  showBack: boolean
}) => (
  <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1, flexWrap: 'wrap', rowGap: 0.5 }}>
    {showBack && (
      <Button size="small" color="inherit" startIcon={<ArrowBackIcon />} onClick={cursor.close}>
        Retour à la liste
      </Button>
    )}
    <Typography variant="body2" color="text.secondary" sx={{ flexGrow: 1, fontVariantNumeric: 'tabular-nums' }}>
      {cursor.position} / {cursor.total}
    </Typography>
    <Button size="small" startIcon={<ChevronLeftIcon />} disabled={!cursor.hasPrevious} onClick={cursor.previous}>
      Précédent
    </Button>
    <Button size="small" endIcon={<ChevronRightIcon />} disabled={!cursor.hasNext} onClick={cursor.next}>
      Suivant
    </Button>
  </Stack>
)
