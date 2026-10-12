import { describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { AdminContext, Configurable, Title } from 'react-admin'
import { CustomAppBar } from './AppBar'
import { veilleuseLightTheme } from '../../theme'

vi.mock('../notifications/NotificationBell', () => ({ NotificationBell: () => null }))
vi.mock('../../modules/search/SearchBar', () => ({ SearchBar: () => null }))
vi.mock('../../hooks/useVoiceRecorder', () => ({
  useVoiceRecorder: () => ({ state: 'idle', duration: 0 }),
}))
vi.mock('../../hooks/useTranscription', () => ({
  useTranscription: () => ({ loading: false, error: null }),
}))

const LONG_TITLE = 'Transactions — [TEST-RECETTE] Compte courant avec un nom vraiment très long pour la barre'

/**
 * react-admin wraps a page's title in a `Configurable` box that is `inline-block`.
 * An atomic inline box is clipped by the bar's `overflow: hidden` but never gets the
 * « … »: a long title ended on half a glyph (MAG-387, recette).
 */
describe('CustomAppBar — the page title', () => {
  test('a long title can end in an ellipsis: nothing in it is an inline-block', async () => {
    render(
      <AdminContext theme={veilleuseLightTheme}>
        {/* jsdom applies rules in the order of the stylesheet, not by specificity: the
            box's own rule must already be in it (with the title's own `sx`, which gives
            it its class), as it is once a real page has rendered. */}
        <Configurable editor={<span />} preferenceKey="warm-up" sx={{ '&.RaConfigurable-editMode': { margin: '2px' } }}>
          <span />
        </Configurable>
        <CustomAppBar />
        <Title title={LONG_TITLE} />
      </AdminContext>,
    )

    const bar = document.getElementById('react-admin-title')!
    await waitFor(() => expect(bar).toHaveTextContent(LONG_TITLE))

    expect(getComputedStyle(bar).textOverflow).toBe('ellipsis')

    const wrapper = bar.firstElementChild as HTMLElement
    expect(wrapper).not.toBeNull()
    expect(getComputedStyle(wrapper).display).not.toBe('inline-block')
    expect(getComputedStyle(wrapper).overflow).toBe('hidden')
    expect(getComputedStyle(wrapper).textOverflow).toBe('ellipsis')
    expect(screen.getByRole('button', { name: 'Chat avec Maggie' })).toBeInTheDocument()
  })
})
