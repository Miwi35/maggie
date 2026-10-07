import { describe, test, expect, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, memoryStore, useSidebarState } from 'react-admin'
import { CustomSidebar } from './Sidebar'
import { veilleuseLightTheme } from '../../theme'
import { PHONE_WIDTH, TABLET_WIDTH, DESKTOP_WIDTH, setViewportWidth, resetViewport } from '../../test/viewport'

/**
 * The menu folds away below `md` and comes back as an overlay (MAG-38).
 *
 * The tablet width is the one worth naming: react-admin draws its own line at
 * `sm`, so at 834px it left a 240px column open beside the page *and* the
 * 380px chat panel. The phone was a drawer already — it only started closed by
 * luck of the same default.
 */

/** The app bar's burger, and a window on `sidebar.open` for the assertions. */
const Burger = () => {
  const [open, setOpen] = useSidebarState()

  return (
    <button onClick={() => setOpen(!open)} data-open={String(open)}>
      burger
    </button>
  )
}

type Store = ReturnType<typeof memoryStore>

/** A fresh element every call: `rerender` with the same one would bail out. */
const shell = (store: Store) => (
  <AdminContext theme={veilleuseLightTheme} store={store}>
    <Burger />
    <CustomSidebar>
      <nav>
        <a href="#/calendar">Calendrier</a>
      </nav>
    </CustomSidebar>
  </AdminContext>
)

const burger = () => screen.getByRole('button', { name: 'burger' })
const calendar = () => screen.queryByRole('link', { name: 'Calendrier' })

describe('CustomSidebar', () => {
  afterEach(() => {
    resetViewport()
  })

  test.each([
    ['a phone', PHONE_WIDTH],
    ['a tablet', TABLET_WIDTH],
  ])('arrives folded on %s', (_label, width) => {
    setViewportWidth(width)

    render(shell(memoryStore()))

    expect(burger()).toHaveAttribute('data-open', 'false')
    expect(calendar()).toBeNull()
  })

  test('the burger brings it over the page, and Escape takes it back', async () => {
    setViewportWidth(PHONE_WIDTH)
    const user = userEvent.setup()

    render(shell(memoryStore()))
    await user.click(burger())

    // An overlay, not a column: the menu is inside a modal, so the page keeps
    // its full width underneath instead of being pushed 240px aside. That is
    // also why the burger cannot be clicked again from here — the modal marks
    // everything behind it `aria-hidden`, exactly as it does for the owner.
    expect(calendar()).toBeVisible()
    expect(calendar()?.closest('.MuiModal-root')).not.toBeNull()

    await user.keyboard('{Escape}')
    expect(calendar()).toBeNull()
  })

  test('stays react-admin’s own sidebar on a desk', () => {
    setViewportWidth(DESKTOP_WIDTH)

    render(shell(memoryStore()))

    expect(burger()).toHaveAttribute('data-open', 'true')
    expect(calendar()).toBeVisible()
    expect(calendar()?.closest('.MuiModal-root')).toBeNull()
  })

  // `sidebar.open` is persisted in react-admin's store, so folding the menu
  // for a narrow window must not leave the same window looking at a 56px rail
  // once it is wide again.
  test('hands the desk its menu back when the window grows again', () => {
    const store = memoryStore()
    setViewportWidth(DESKTOP_WIDTH)
    const { rerender } = render(shell(store))

    setViewportWidth(PHONE_WIDTH)
    rerender(shell(store))
    expect(burger()).toHaveAttribute('data-open', 'false')

    setViewportWidth(DESKTOP_WIDTH)
    rerender(shell(store))
    expect(burger()).toHaveAttribute('data-open', 'true')
  })
})
