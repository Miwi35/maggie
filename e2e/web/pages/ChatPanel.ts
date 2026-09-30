import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { parseAgUiStream } from '../helpers/agui.js'
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
 */
export class ChatPanel {
  readonly panel: Locator
  readonly input: Locator

  constructor(private readonly page: Page) {
    this.panel = page.getByTestId('chat-panel')
    this.input = this.panel.getByPlaceholder('Demande à Maggie...')
  }

  /** The panel opens with the shell; on a narrow viewport it may need the AppBar button. */
  async open(): Promise<void> {
    if (await this.input.isVisible()) {
      return
    }

    await this.page.getByRole('button', { name: /chat/i }).first().click()
    await expect(this.input).toBeVisible()
  }

  /**
   * Sends a message and returns the AG-UI events of the whole run.
   *
   * Fails if the run never reaches `RUN_FINISHED`: a hung tool loop must read
   * as a hung tool loop, not as a missing answer.
   */
  async send(message: string): Promise<AgUiEvent[]> {
    await this.open()

    const stream = this.page.waitForResponse(
      (response) =>
        response.url().includes('/agent/chat/stream') && response.request().method() === 'POST',
      { timeout: 60_000 },
    )

    await this.input.fill(message)
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
}
