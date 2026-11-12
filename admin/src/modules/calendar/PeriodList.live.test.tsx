import { vi } from 'vitest'
import { liveListSuite } from '../../test/mercure'
import { PeriodList } from './PeriodList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

liveListSuite({
  name: 'PeriodList',
  screen: () => <PeriodList />,
  resource: 'periods',
  topic: '/api/periods/{id}',
  listed: { id: '/api/periods/01GARDE', name: 'Garde', agenda: '/api/agendas/a', allDay: true, startDate: '2026-10-18', endDate: '2026-10-19', rrule: null },
  listedText: 'Garde',
  created: { payload: { name: 'Vacances', allDay: true, startDate: '2026-10-20', endDate: '2026-10-22', rrule: null }, text: 'Vacances' },
  updated: { payload: { name: 'Garde de Léo' }, text: 'Garde de Léo' },
})
