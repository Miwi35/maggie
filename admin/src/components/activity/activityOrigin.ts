import { useCallback } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

/** What a launcher hands the activity, for leaving to go back where it was. */
export interface ActivityLaunchState {
  from?: string
}

/** The page the activity was launched from, or `fallback` when it was opened by its address. */
export const useActivityOrigin = (fallback: string): (() => void) => {
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as ActivityLaunchState | null)?.from

  return useCallback(() => {
    navigate(from && from !== location.pathname ? from : fallback)
  }, [navigate, from, location.pathname, fallback])
}
