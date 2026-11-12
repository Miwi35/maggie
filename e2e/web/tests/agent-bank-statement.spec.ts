import { test, expect } from '../fixtures/index.js'
import { calledTools, toolResults } from '../helpers/agui.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { ROUTES } from '../pages/routes.js'

/**
 * « Montre-moi les virements de N26 en septembre », puis « Importe-les » — MAG-802.
 *
 * The bank (WireMock) holds twelve movements for September. Maggie reads the month through
 * `read_bank_statement` — nothing is stored — then imports only the two N26 transfers the owner
 * chose, through `import_bank_transactions`. What is proved is the chain through the real MCP tools
 * and what the API holds afterwards: the two chosen movements, none of the ten others.
 * `89-bank-statement-*.yaml` script the model's side; picking the right refs is the eval suite's.
 */

const CHOSEN = ['Virement N26 Léo septembre', 'Virement N26 Léo cantine']
const OTHERS = ['CARREFOUR MARKET', 'SALAIRE ACME']

interface Account {
  id: string
}

interface Row {
  label: string
}

test('Maggie reads a bank statement without storing it, then imports only the chosen movements — MAG-802', async ({ bankStatementUser }) => {
  const { api, page } = bankStatementUser

  const accounts = await getCollection<Account>(api, '/api/accounts')
  expect(accounts, 'the bank statement account is the only one this user owns').toHaveLength(1)
  const accountId = accounts[0].id

  const shell = new AdminShell(page)
  await shell.goto(ROUTES.home)
  await shell.expectLoaded()
  const chat = new ChatPanel(page)

  const read = await chat.send(`Montre-moi les virements de N26 en septembre, compte ${accountId}`)
  expect(calledTools(read), 'the statement tool never ran — did the scenario stop matching?').toEqual(['read_bank_statement'])
  expect(toolResults(read)).toEqual([expect.objectContaining({ toolName: 'read_bank_statement', status: 'success' })])

  const afterRead = await getCollection<Row>(api, '/api/transactions?itemsPerPage=100')
  expect(afterRead, 'reading a statement must store nothing').toEqual([])

  const imported = await chat.send(`Importe-les : les deux virements de N26 de septembre, compte ${accountId}`)
  expect(calledTools(imported)).toEqual(['import_bank_transactions'])
  expect(toolResults(imported)).toEqual([expect.objectContaining({ toolName: 'import_bank_transactions', status: 'success' })])

  for (const label of CHOSEN) {
    await waitForIndexed<Row>(api, '/api/transactions?itemsPerPage=100', (member) => member.label === label, { what: label })
  }

  const stored = (await getCollection<Row>(api, '/api/transactions?itemsPerPage=100')).map((member) => member.label)
  expect([...stored].sort()).toEqual([...CHOSEN].sort())
  for (const label of OTHERS) {
    expect(stored).not.toContain(label)
  }
})
