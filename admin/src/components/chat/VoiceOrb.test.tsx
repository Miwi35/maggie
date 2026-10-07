import { describe, test, expect, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithTheme } from '../../test/renderWithTheme'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import { VoiceOrb } from './VoiceOrb'

describe.each([
  ['dark', veilleuseDarkTheme],
  ['light', veilleuseLightTheme],
])('VoiceOrb in the %s theme', (_mode, theme) => {
  test('listens: orb, two rings, waveform, duration and both pills', async () => {
    const onFinish = vi.fn()
    const onCancel = vi.fn()
    const user = userEvent.setup()
    renderWithTheme(<VoiceOrb phase="listening" duration={7} onFinish={onFinish} onCancel={onCancel} />, theme)

    expect(screen.getByText('Je t’écoute…')).toBeInTheDocument()
    expect(screen.getByText('7s')).toBeInTheDocument()
    expect(screen.getAllByTestId('voice-orb-ring')).toHaveLength(2)
    expect(screen.getAllByTestId('voice-orb-bar').length).toBeGreaterThan(3)

    await user.click(screen.getByRole('button', { name: 'Terminer' }))
    await user.click(screen.getByRole('button', { name: 'Annuler' }))
    expect(onFinish).toHaveBeenCalledOnce()
    expect(onCancel).toHaveBeenCalledOnce()
  })

  test('transcribes: no rings, no waveform, nothing to press', () => {
    renderWithTheme(<VoiceOrb phase="transcribing" duration={7} onFinish={vi.fn()} onCancel={vi.fn()} />, theme)

    expect(screen.getByText('Transcription…')).toBeInTheDocument()
    expect(screen.queryByTestId('voice-orb-ring')).toBeNull()
    expect(screen.queryByTestId('voice-orb-bar')).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
  })
})
