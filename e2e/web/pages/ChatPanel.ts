import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { parseAgUiStream } from '../helpers/agui.js'
import { withChatLock } from '../helpers/chatLock.js'
import type { AgUiEvent } from '../helpers/agui.js'

/**
 * Talking to Maggie from the admin's side panel.
 *
 * {@link send} is the helper the ticket asks for: it types a message and waits
 * for the AG-UI run to *finish*, not merely for a bubble to appear. Waiting on
 * the DOM alone is how a chat journey goes flaky — the first delta lands in
 * milliseconds while the tool loop is still running, so an assertion written
 * against the bubble reads a half-built answer.
 *
 * It waits on the `POST /agent/chat/stream` response instead, whose body only
 * completes when the stream does, and hands back the parsed events. With
 * `LLM_PROVIDER=fake` that is the production gateway, tool loop and MCP client
 * — only the model is scripted.
 *
 * The panel has two tabs. "Chat" is the conversation; "Mind" is what Maggie is
 * holding — the contexts she has open and the tools she has just run. Both are
 * fed by the same stream, so the Mind assertions are the visible half of what
 * {@link send} returns, and worth making: the state behind them lives in
 * `Layout`, above the widget, and a re-render that dropped it would leave the
 * events perfectly correct and the panel empty.
 */
export class ChatPanel {
  readonly panel: Locator
  readonly input: Locator
  readonly chatTab: Locator
  readonly mindTab: Locator
  /** The Mind panel's "Contextes" section — present even while it is empty. */
  readonly contexts: Locator
  /** The Mind panel's "Activité" section. */
  readonly activity: Locator
  /** The microphone. Its name flips to "Arrêter la dictée" while it records. */
  readonly dictateButton: Locator
  readonly stopDictationButton: Locator
  /** The panel's own way out — the only one once it is a full-screen sheet. */
  readonly closeButton: Locator

  constructor(private readonly page: Page) {
    this.panel = page.getByTestId('chat-panel')
    this.input = this.panel.getByPlaceholder('Demande à Maggie...')
    this.chatTab = this.panel.getByRole('tab', { name: 'Chat' })
    this.mindTab = this.panel.getByRole('tab', { name: 'Mind' })
    this.closeButton = this.panel.getByRole('button', { name: 'Fermer la conversation' })
    this.contexts = this.panel.getByTestId('mind-contexts')
    this.activity = this.panel.getByTestId('mind-activity')
    this.dictateButton = this.panel.getByRole('button', { name: 'Dicter' })
    this.stopDictationButton = this.panel.getByRole('button', { name: 'Arrêter la dictée' })
  }

  /**
   * The panel opens with the shell above `md`; below it the app bar's button
   * is what brings it in (MAG-38). Idempotent.
   */
  async open(): Promise<void> {
    if (await this.input.isVisible()) {
      return
    }

    await this.page.getByRole('banner').getByRole('button', { name: 'Chat avec Maggie' }).click()
    await expect(this.input).toBeVisible()
  }

  /**
   * Puts it away again.
   *
   * Through the panel's own button rather than the app bar's: below `md` the
   * panel is a modal sheet, so everything behind it — the app bar included —
   * is `aria-hidden` and unreachable by role, for a journey exactly as for a
   * screen reader.
   */
  async close(): Promise<void> {
    await this.closeButton.click()
    await expect(this.input).toBeHidden()
  }

  /** Switches to the Mind tab. The chat tab keeps its messages behind it. */
  async openMind(): Promise<void> {
    await this.open()
    await this.mindTab.click()
    await expect(this.contexts).toBeVisible()
  }

  /** Back to the conversation. */
  async openChat(): Promise<void> {
    await this.chatTab.click()
    await expect(this.input).toBeVisible()
  }

  /**
   * Dictates a message: records for a moment, stops, and waits for the
   * transcription to answer.
   *
   * Returns what `POST /agent/transcribe` said, so a journey can assert on
   * `raw` (Whisper, which is WireMock here) and `clean` (the model's tidy-up)
   * separately. The cleaned sentence reaches the input a beat later: assert on
   * the input, it retries.
   */
  async dictate(): Promise<{ raw: string; clean: string }> {
    await this.open()

    await this.dictateButton.click()
    // Chromium's fake capture device has to produce a chunk before the
    // recorder has anything to hand over; `start(250)` flushes every 250 ms.
    await expect(this.stopDictationButton).toBeVisible()
    // eslint-disable-next-line playwright/no-wait-for-timeout
    await this.page.waitForTimeout(600)

    const transcription = this.page.waitForResponse(
      (response) =>
        response.url().includes('/agent/transcribe') && response.request().method() === 'POST',
      { timeout: 30_000 },
    )
    await this.stopDictationButton.click()

    const response = await transcription
    expect(response.status(), 'the transcription was refused').toBe(200)

    return (await response.json()) as { raw: string; clean: string }
  }

  /**
   * Sends a message and returns the AG-UI events of the whole run.
   *
   * Fails if the run never reaches `RUN_FINISHED`: a hung tool loop must read
   * as a hung tool loop, not as a missing answer.
   */
  async send(message: string): Promise<AgUiEvent[]> {
    await this.open()
    await this.input.fill(message)

    return this.submit()
  }

  /** Sends whatever the input holds — a dictated sentence, say — and waits for the run to end. */
  async submit(): Promise<AgUiEvent[]> {
    // One run at a time across the workers: see `withChatLock`.
    return withChatLock(async () => {
      const stream = this.page.waitForResponse(
        (response) =>
          response.url().includes('/agent/chat/stream') && response.request().method() === 'POST',
        { timeout: 60_000 },
      )

      await this.input.press('Enter')

      const response = await stream
      expect(response.status(), 'the agent refused the message').toBe(200)

      // Resolves when the stream closes, which is precisely "the run is over".
      const events = parseAgUiStream(await response.text())

      expect(
        events.map((event) => event.type),
        `the run never finished — events seen: ${events.map((e) => e.type).join(', ') || '(none)'}`,
      ).toContain('RUN_FINISHED')

      return events
    })
  }

  /**
   * A bubble carrying `text`, once the run has finished.
   *
   * Scoped to the panel: Maggie answers in the same French as the page behind
   * her — "cette semaine", "déjeuner avec Alex" — and an unscoped locator
   * matches both, which is a strict-mode failure with nothing to teach.
   */
  message(text: string | RegExp): Locator {
    return this.panel.getByText(text).first()
  }

  /**
   * Every bubble whose text is exactly `text` — the locator to count.
   *
   * Counting is the point: `176c40c` fixed three independent ways the same
   * message could be shown twice (a React 18 StrictMode double-invoke of an
   * impure updater, one `TEXT_MESSAGE_START`/`END` cycle per tool round, and
   * the user's message doubled in the model's context). `message()` would be
   * green through all three.
   */
  bubbles(text: string): Locator {
    return this.panel.getByText(text, { exact: true })
  }

  /** Every context line in the Mind panel — the locator to count. */
  get contextItems(): Locator {
    return this.panel.getByTestId('mind-context')
  }

  /** A context line in the Mind panel, by its label. */
  context(label: string): Locator {
    return this.contextItems.filter({ hasText: label })
  }

  /**
   * What a summarized thread is about, under its label (MAG-11).
   *
   * Only the threads Maggie has already summarized have one, so this is also how
   * a journey tells "the summary was written" from "the panel rendered".
   */
  get contextSummaries(): Locator {
    return this.panel.getByTestId('mind-context-summary')
  }

  /** Every validation card in the thread — `data-status` carries where it stands (MAG-6). */
  get approvalCards(): Locator {
    return this.panel.getByTestId('approval-card')
  }

  /** The validation card showing `text` — a tool name, or one of its arguments. */
  approvalCard(text: string): Locator {
    return this.approvalCards.filter({ hasText: text })
  }

  /** A tool call in the Mind panel, by name. `data-status` carries its outcome. */
  toolCall(name: string): Locator {
    return this.panel.getByTestId('mind-tool-call').filter({ hasText: name })
  }
}
