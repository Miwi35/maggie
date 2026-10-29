import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithTheme } from '../../test/renderWithTheme'
import { VoiceBubble } from './VoiceBubble'

/** Maggie's voice message in the thread (MAG-686): play, pause, end, error, speed, transcript. */

const AUDIO = {
  type: 'audio/mpeg',
  durationMs: 83000,
  size: 1234,
  url: '/agent/messages/m-1/audio',
  transcript: '[warmly] Bonjour, voici ta journée. [softly] Bonne matinée.',
}

class FakeAudio extends EventTarget {
  static last: FakeAudio | null = null
  currentTime = 0
  playbackRate = 1
  play = vi.fn(() => Promise.resolve())
  pause = vi.fn()
  constructor(public src: string) {
    super()
    FakeAudio.last = this
  }
}

function stubFetch(status = 200) {
  const fetchMock = vi.fn(() =>
    Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve({ url: 'https://bucket/signed.mp3' }) }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const bubble = () =>
  renderWithTheme(<VoiceBubble audio={AUDIO} authHeaders={() => ({ Authorization: 'Bearer t' })} />)

describe('VoiceBubble', () => {
  beforeEach(() => {
    vi.stubGlobal('Audio', FakeAudio)
    FakeAudio.last = null
  })
  afterEach(() => vi.unstubAllGlobals())

  test('shows the duration before playing', () => {
    stubFetch()
    bubble()
    expect(screen.getByTestId('voice-duration')).toHaveTextContent('1:23')
  })

  test('plays the signed URL asked with the owner token, then pauses', async () => {
    const fetchMock = stubFetch()
    const user = userEvent.setup()
    bubble()

    await user.click(screen.getByRole('button', { name: 'Lecture' }))

    await screen.findByRole('button', { name: 'Pause' })
    expect(fetchMock).toHaveBeenCalledWith(AUDIO.url, { headers: { Authorization: 'Bearer t' } })
    expect(FakeAudio.last?.src).toBe('https://bucket/signed.mp3')
    expect(FakeAudio.last?.play).toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Pause' }))
    expect(FakeAudio.last?.pause).toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Lecture' })).toBeInTheDocument()
  })

  test('shows the progress while it plays and goes back to play at the end', async () => {
    stubFetch()
    const user = userEvent.setup()
    bubble()
    await user.click(screen.getByRole('button', { name: 'Lecture' }))
    await screen.findByRole('button', { name: 'Pause' })

    act(() => {
      FakeAudio.last!.currentTime = 12
      FakeAudio.last!.dispatchEvent(new Event('timeupdate'))
    })
    expect(screen.getByTestId('voice-duration')).toHaveTextContent('0:12')

    act(() => {
      FakeAudio.last!.dispatchEvent(new Event('ended'))
    })
    expect(screen.getByRole('button', { name: 'Lecture' })).toBeInTheDocument()
    expect(screen.getByTestId('voice-duration')).toHaveTextContent('1:23')
  })

  test('goes ×1 → ×1,5 → ×2 → ×1 and applies it to the playing audio', async () => {
    stubFetch()
    const user = userEvent.setup()
    bubble()
    await user.click(screen.getByRole('button', { name: 'Lecture' }))
    await screen.findByRole('button', { name: 'Pause' })

    await user.click(screen.getByRole('button', { name: 'Vitesse ×1' }))
    expect(screen.getByRole('button', { name: 'Vitesse ×1,5' })).toBeInTheDocument()
    expect(FakeAudio.last?.playbackRate).toBe(1.5)
    await user.click(screen.getByRole('button', { name: 'Vitesse ×1,5' }))
    expect(FakeAudio.last?.playbackRate).toBe(2)
    await user.click(screen.getByRole('button', { name: 'Vitesse ×2' }))
    expect(FakeAudio.last?.playbackRate).toBe(1)
  })

  test('unfolds the transcript without voice tags', async () => {
    stubFetch()
    const user = userEvent.setup()
    bubble()
    expect(screen.queryByTestId('voice-transcript')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Transcription' }))

    const transcript = screen.getByTestId('voice-transcript')
    expect(transcript).toHaveTextContent('Bonjour, voici ta journée. Bonne matinée.')
    expect(transcript.textContent).not.toMatch(/\[/)
  })

  test('says the audio is missing in the bubble, and can be tried again', async () => {
    const fetchMock = stubFetch(404)
    const user = userEvent.setup()
    bubble()

    await user.click(screen.getByRole('button', { name: 'Lecture' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Audio introuvable')
    expect(screen.getByRole('button', { name: 'Lecture' })).toBeInTheDocument()

    fetchMock.mockResolvedValueOnce({ ok: true, status: 200, json: () => Promise.resolve({ url: 'https://bucket/x.mp3' }) } as never)
    await user.click(screen.getByRole('button', { name: 'Lecture' }))
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  })

  test('an error of the player itself shows the same message', async () => {
    stubFetch()
    const user = userEvent.setup()
    bubble()
    await user.click(screen.getByRole('button', { name: 'Lecture' }))
    await screen.findByRole('button', { name: 'Pause' })

    act(() => {
      FakeAudio.last!.dispatchEvent(new Event('error'))
    })

    expect(screen.getByRole('alert')).toHaveTextContent('Audio introuvable')
  })
})
