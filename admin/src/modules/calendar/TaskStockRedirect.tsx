import { Navigate, useLocation } from 'react-router-dom'

/** The old « À venir » and « Un jour » links (notifications, bookmarks) open « Tâches », the task kept. */
export const TaskStockRedirect = () => <Navigate to={{ pathname: '/tasks/stock', search: useLocation().search }} replace />
