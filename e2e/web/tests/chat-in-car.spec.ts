import { test, expect } from '../fixtures/index.js'
import { assistantText, parseAgUiStream } from '../helpers/agui.js'
import { withChatLock } from '../helpers/chatLock.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * Maggie knows the owner is driving (MAG-684, extends MAG-99).
 *
 * The phone sends `in_car` with the message when it projects to Android Auto. The fake model only
 * answers the scenario `in-car-driving` if the system prompt it receives holds the driving
 * instruction: the same question without the flag falls through to the default scenario.
 */

const ANSWER = "Vous avez un rendez-vous à quatorze heures, monsieur. Rien d'autre."

// The second account, for the same reason as chat-client-leaves.spec.ts.
test('the driving instruction reaches the model only when the message says it is in the car', async ({ otherUser }) => {
  const { page, session } = otherUser
  await new HomePage(page).open()

  await withChatLock(async () => {
    const ask = (message: string, inCar: boolean) =>
      page.evaluate(
        async ({ message, inCar, token }) => {
          const response = await fetch('/agent/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
            body: JSON.stringify({ message, in_car: inCar }),
          })
          return response.text()
        },
        { message, inCar, token: session.token },
      )

    const stamp = Date.now()
    const driving = parseAgUiStream(await ask(`Qu'ai-je de prévu, je suis en voiture ? ${stamp}`, true))
    expect(assistantText(driving)).toBe(ANSWER)

    const parked = parseAgUiStream(await ask(`Qu'ai-je de prévu, je suis en voiture ? ${stamp + 1}`, false))
    expect(assistantText(parked)).not.toBe(ANSWER)
  })
})
