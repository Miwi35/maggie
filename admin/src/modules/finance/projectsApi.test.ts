import { describe, expect, test } from 'vitest'
import { eurosToCents, validateProject } from './projectForm'
import { joinNames, patchProject, projectDateLabel, toProject } from './projectsApi'

describe('projects', () => {
  test.each([
    [{ dateMode: 'none', targetOn: null }, 'Sans date'],
    [{ dateMode: 'fixed', targetOn: '2027-03-01' }, 'le 1er mars 2027'],
    [{ dateMode: 'fixed', targetOn: '2027-01-15' }, 'le 15 janvier 2027'],
    [{ dateMode: 'deadline', targetOn: '2027-08-31' }, 'au plus tard le 31 août 2027'],
  ] as const)('%o reads « %s »', (project, label) => {
    expect(projectDateLabel(project)).toBe(label)
  })

  test('names one, two or three projects the French way', () => {
    expect(joinNames(['Déménagement'])).toBe('Déménagement')
    expect(joinNames(['Déménagement', 'Voiture'])).toBe('Déménagement et Voiture')
    expect(joinNames(['A', 'B', 'C'])).toBe('A, B et C')
  })

  test('reads dependencies as bare ids, IRIs (REST) or ids (reorder) alike', () => {
    expect(toProject({ '@id': '/api/finance/projects/A', dependsOn: ['/api/finance/projects/B'] }).dependsOn).toEqual(['B'])
    expect(toProject({ id: 'A', dependsOn: ['B'] }).id).toBe('A')
  })

  test('a Mercure update changes only the fields it carries', () => {
    const before = toProject({ id: 'A', name: 'Déménagement', position: 0, amountCents: 100 })
    const after = patchProject(before, { '@id': '/api/finance/projects/A', position: 2 })

    expect(after).toEqual({ ...before, position: 2 })
  })

  test.each([
    ['1 500,50', 150050],
    ['1 500,5 €', 150050],
    ['12.3', 1230],
    ['0', 0],
    ['douze', null],
    ['', null],
  ])('« %s » is %s cents', (typed, cents) => {
    expect(eurosToCents(typed)).toBe(cents)
  })

  test('the form refuses what the API would', () => {
    const valid = { name: 'Canapé', amount: '1200', wellbeing: 'comfort', dateMode: 'none', targetOn: '', dependsOn: [], note: '' } as const

    expect(validateProject({ ...valid, dependsOn: [] })).toEqual({})
    expect(validateProject({ ...valid, dependsOn: [], dateMode: 'deadline' })).toEqual({ targetOn: 'Choisissez la date limite.' })
    expect(validateProject({ ...valid, dependsOn: [], name: ' ', amount: '-3' })).toEqual({
      name: 'Le nom est obligatoire.',
      amount: 'Indiquez un montant supérieur à 0.',
    })
  })
})
