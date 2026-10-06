import { useEffect, useRef } from 'react'
import { Sidebar as RASidebar, useSidebarState } from 'react-admin'
import Drawer from '@mui/material/Drawer'
import type { SidebarProps } from 'react-admin'
import { useNarrowScreen } from '../../hooks/useNarrowScreen'

/** Wide enough to read a nested entry ("Revue mensuelle"), never wider than the window. */
const NARROW_DRAWER_WIDTH = 'min(300px, 85vw)'

/**
 * The menu, folded away below `md` (MAG-38).
 *
 * React-admin draws its own line at `sm` (600px): below it the sidebar is a
 * temporary drawer over the page, above it a permanent column that pushes the
 * page aside — 240px open, a 56px rail closed. On an 834px tablet that
 * permanent column opens by default and takes a third of the width before the
 * chat panel has taken its own.
 *
 * So below `md` this replaces it with an overlay drawer that starts closed.
 * The state is still react-admin's `sidebar.open`, which is what makes the rest
 * of the framework agree with us: the app bar's burger toggles it, and
 * `MenuItemLink` — which already closes the sidebar below `md` on tap — makes
 * the drawer go away as soon as a destination is picked. `CustomMenu` reads the
 * same flag to decide whether to draw the collapsible groups, so the drawer
 * shows the whole menu rather than the rail's two icons.
 *
 * Above `md` nothing changes: react-admin's own sidebar, and the owner's choice
 * to collapse it to a rail is his to keep.
 */
export const CustomSidebar = ({ children, ...props }: SidebarProps) => {
  const isNarrow = useNarrowScreen()
  const [open, setOpen] = useSidebarState()
  // `sidebar.open` is persisted, so folding the menu for a narrow window would
  // otherwise be waiting as a 56px rail the next time the same window is wide.
  // Remember what wide was, hand it back when wide returns — in a ref, so the
  // memory is session-scoped on purpose: a phone and a desk are two browsers
  // with two stores, and the only case to cover is one window resized.
  const wideOpen = useRef<boolean | null>(null)
  /**
   * One paint's worth of grace, while the fold below catches up.
   *
   * `sidebar.open` defaults to open above `sm`, so on an 834px tablet the
   * store says "open" before the effect has had a chance to say otherwise.
   * Drawing the drawer open for that one frame did more than flash the menu
   * over the page: MUI's modal was left mid-transition, invisible and still
   * swallowing every tap behind it, which is how the whole app bar became
   * unclickable on a tablet. Shut until the fold is applied; the store alone
   * decides after that.
   *
   * Both refs are render-derived caches — nothing outside this component can
   * read them — so re-arming them while rendering is safe, and it covers the
   * window dragged across 900px as well as the one that opens there.
   */
  const folding = useRef(isNarrow)
  const wasNarrow = useRef(isNarrow)

  if (wasNarrow.current !== isNarrow) {
    wasNarrow.current = isNarrow
    folding.current = isNarrow
  }

  useEffect(() => {
    folding.current = false

    if (isNarrow) {
      wideOpen.current = open
      setOpen(false)

      return
    }

    if (wideOpen.current !== null) {
      setOpen(wideOpen.current)
      wideOpen.current = null
    }
    // Deliberately keyed on the width alone: `open` is read here, not watched.
    // Listing it would undo the owner's very next tap on the burger.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isNarrow])

  if (!isNarrow) {
    return <RASidebar {...props}>{children}</RASidebar>
  }

  return (
    <Drawer
      variant="temporary"
      open={open && !folding.current}
      onClose={() => setOpen(false)}
      slotProps={{ paper: { sx: { width: NARROW_DRAWER_WIDTH, pt: 1 } } }}
    >
      {children}
    </Drawer>
  )
}
