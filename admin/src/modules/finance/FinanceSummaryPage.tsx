import { useCallback, useState } from 'react'
import Alert from '@mui/material/Alert'
import LinearProgress from '@mui/material/LinearProgress'
import MuiLink from '@mui/material/Link'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import { Title } from 'react-admin'
import { Link, useParams } from 'react-router-dom'
import { FINANCE_DASHBOARD_PATH, FINANCE_MENUS, financeAspectPath } from '../../components/layout/navigation'
import type { NavMenu } from '../../components/layout/navigation'
import { ASPECT_CONTENT } from './FinanceAspectContent'
import { AspectProbes } from './FinanceAspectProbes'

type Presence = Record<string, boolean>

/**
 * The aspect a menu opens on when the URL names none: the first with data, or
 * the first of all when none has any. `null` while an earlier aspect has not
 * answered yet — a later one cannot be picked before it.
 */
const defaultAspect = (menu: NavMenu, presence: Presence): string | null => {
  for (const aspect of menu.aspects) {
    if (presence[aspect.id] === undefined) return null
    if (presence[aspect.id]) return aspect.id
  }

  return menu.aspects[0].id
}

const MenuTabs = ({ menu, requested }: { menu: NavMenu; requested: string | undefined }) => {
  const [presence, setPresence] = useState<Presence>({})
  const onResult = useCallback((aspectId: string, hasData: boolean) => {
    setPresence((current) => (current[aspectId] === hasData ? current : { ...current, [aspectId]: hasData }))
  }, [])

  // Opening a tab forgets the answers, so the bare menu asks again when it comes back: what has data may have changed.
  const [previous, setPrevious] = useState(requested)
  if (previous !== requested) {
    setPrevious(requested)
    if (requested) setPresence({})
  }

  const active = requested ?? (menu.aspects.length === 1 ? menu.aspects[0].id : defaultAspect(menu, presence))
  const Content = active ? ASPECT_CONTENT[active] : undefined

  return (
    <>
      {menu.aspects.length > 1 && (
        <Tabs
          value={active ?? false}
          variant="scrollable"
          scrollButtons="auto"
          allowScrollButtonsMobile
          aria-label={menu.label}
          sx={{ mb: 2 }}
        >
          {menu.aspects.map((aspect) => (
            <Tab
              key={aspect.id}
              value={aspect.id}
              label={aspect.label}
              component={Link}
              to={financeAspectPath(menu.key, aspect.id)}
              data-testid={`aspect-tab-${aspect.id}`}
            />
          ))}
        </Tabs>
      )}

      {!requested && active === null && (
        <>
          <Title title={`Finance · ${menu.label}`} />
          <AspectProbes aspectIds={menu.aspects.map((aspect) => aspect.id)} onResult={onResult} />
          <LinearProgress aria-label="Chargement" />
        </>
      )}

      {Content && (
        <div role={menu.aspects.length > 1 ? 'tabpanel' : undefined} data-testid="aspect-panel" data-aspect={active}>
          <Content />
        </div>
      )}
    </>
  )
}

/**
 * A Finance menu's page (MAG-427, MAG-518): the content of its aspects, one tab
 * each — not a summary of them. The tab is in the path
 * (`/finance/summary/<menu>/<aspect>`) so a link and the back button land on
 * it; without one the menu opens on its first aspect that has data. A menu with
 * a single aspect has no tabs: the content, directly.
 */
export const FinanceSummaryPage = () => {
  const { key, aspect } = useParams()
  const menu = FINANCE_MENUS.find((candidate) => candidate.key === key && candidate.aspects.length > 0)

  if (!menu || (aspect !== undefined && !menu.aspects.some((candidate) => candidate.id === aspect))) {
    return (
      <>
        <Title title="Finance" />
        <Alert
          severity="warning"
          action={
            <MuiLink component={Link} to={FINANCE_DASHBOARD_PATH} color="inherit">
              Retour au Dashboard
            </MuiLink>
          }
        >
          Ce menu n'existe pas.
        </Alert>
      </>
    )
  }

  return <MenuTabs key={menu.key} menu={menu} requested={aspect} />
}
