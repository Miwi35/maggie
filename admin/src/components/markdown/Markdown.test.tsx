import { describe, test, expect, vi } from 'vitest'
import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MarkdownEditor } from './MarkdownEditor'
import { MarkdownText } from './MarkdownText'
import { applyFormat, checkboxCount, clearFormat, continueList, toggleCheckbox } from './markdownEdit'

function Harness({ initial = '' }: { initial?: string }) {
  const [value, setValue] = useState(initial)

  return <MarkdownEditor label="Notes" value={value} onChange={setValue} />
}

const field = () => screen.getByLabelText('Notes') as HTMLTextAreaElement

describe('MarkdownEditor', () => {
  test('the formatting bar is rendered without focus', () => {
    render(<Harness />)
    for (const name of ['Case à cocher', 'Puces', 'Gras', 'Intertitre', 'Lien', 'Retirer la mise en forme']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    expect(field()).not.toHaveFocus()
  })

  test.each([
    ['Case à cocher', '- [ ] '],
    ['Puces', '- '],
    ['Intertitre', '# '],
    ['Gras', '**texte**'],
    ['Lien', '[texte](https://)'],
  ])('%s inserts its mark', async (name, expected) => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.click(screen.getByRole('button', { name }))
    expect(field().value).toBe(expected)
  })

  test('Enter in a list continues it, and on an empty item leaves it', async () => {
    const user = userEvent.setup()
    render(<Harness initial="- [ ] Douche" />)
    await user.click(field())
    await user.keyboard('{Control>}{End}{/Control}{Enter}')
    expect(field().value).toBe('- [ ] Douche\n- [ ] ')
    await user.keyboard('{Enter}')
    expect(field().value).toBe('- [ ] Douche\n')
  })

  test('Ctrl+B wraps the selection in bold', async () => {
    const user = userEvent.setup()
    render(<Harness initial="mot" />)
    field().focus()
    field().setSelectionRange(0, 3)
    await user.keyboard('{Control>}b{/Control}')
    expect(field().value).toBe('**mot**')
  })
})

describe('markdownEdit', () => {
  test('a line prefix toggles', () => {
    expect(applyFormat('- a', 3, 3, 'bullet').value).toBe('a')
    expect(applyFormat('a\nb', 3, 3, 'checkbox').value).toBe('a\n- [ ] b')
  })

  test('clearFormat strips the marks', () => {
    expect(clearFormat('- [x] **a** [b](http://c)', 0, 0).value).toBe('a b')
  })

  test('continueList ignores plain lines', () => {
    expect(continueList('plain', 5)).toBeNull()
  })

  test('toggleCheckbox and count', () => {
    expect(toggleCheckbox('- [ ] a\n- [x] b', 0)).toBe('- [x] a\n- [x] b')
    expect(checkboxCount('- [ ] a\n- [x] b\nx')).toEqual({ done: 1, total: 2 })
  })
})

describe('MarkdownText', () => {
  test('a plain text shows as typed', () => {
    render(<MarkdownText value={'Les cartons\nsont à la cave'} />)
    expect(screen.getByText(/Les cartons/).textContent).toBe('Les cartons\nsont à la cave')
    expect(screen.queryByTestId('markdown-progress')).toBeNull()
  })

  test('a box is ticked in place and rewrites the Markdown', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<MarkdownText value={'- [ ] Douche solaire\n- [x] Courses'} onChange={onChange} />)
    expect(screen.getByTestId('markdown-progress')).toHaveTextContent('1/2')
    await user.click(screen.getByRole('checkbox', { name: 'Douche solaire' }))
    expect(onChange).toHaveBeenCalledWith('- [x] Douche solaire\n- [x] Courses')
  })
})
