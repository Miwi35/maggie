import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Checkbox from '@mui/material/Checkbox'
import LinearProgress from '@mui/material/LinearProgress'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { useGetList, useNotify } from 'react-admin'
import { Placeholder } from '../../components/list/ListEmpty'
import { Amount } from './AmountField'
import { idOf } from './categorizationRules'

interface Suggestion {
  pattern: string
  occurrences: number
  totalCents: number
  direction: string
  categoryId: string | null
  categoryName: string | null
  samples: string[]
}

interface Choice {
  selected: boolean
  categoryId: string
}

const authHeaders = (extra: Record<string, string> = {}) => {
  const token = localStorage.getItem('token')

  return {
    Accept: 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...extra,
  }
}

/**
 * The rules the statement already implies.
 *
 * Writing rules from a blank page means remembering how your bank spells each
 * shop, which nobody does. Here the history answers instead: the merchants
 * that come back, what they cost, and a heading where it is obvious.
 */
export const RuleSuggestions = () => {
  const [suggestions, setSuggestions] = useState<Suggestion[]>([])
  const [choices, setChoices] = useState<Record<string, Choice>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const notify = useNotify()
  const { data: categories } = useGetList('categories', {
    pagination: { page: 1, perPage: 200 },
  })

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch('/api/finance/categorization-rules/suggestions', {
        headers: authHeaders(),
      })
      if (!res.ok) {
        return
      }
      const body = (await res.json()) as { suggestions: Suggestion[] }
      setSuggestions(body.suggestions)
      setChoices(
        Object.fromEntries(
          body.suggestions.map((suggestion) => [
            suggestion.pattern,
            // Only what already has a heading starts ticked: the rest is a
            // question, and a question should not answer itself.
            {
              selected: suggestion.categoryId !== null,
              categoryId: suggestion.categoryId ?? '',
            },
          ]),
        ),
      )
    } catch {
      notify('Les suggestions sont indisponibles', { type: 'error' })
    } finally {
      setLoading(false)
    }
  }, [notify])

  useEffect(() => {
    load()
  }, [load])

  const update = (pattern: string, change: Partial<Choice>) => {
    setChoices((current) => ({
      ...current,
      [pattern]: { ...current[pattern], ...change },
    }))
  }

  const kept = suggestions.filter(
    (suggestion) =>
      choices[suggestion.pattern]?.selected && choices[suggestion.pattern]?.categoryId,
  )

  const onCreate = async () => {
    setSaving(true)
    try {
      const res = await fetch('/api/finance/categorization-rules/suggestions', {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({
          rules: kept.map((suggestion) => ({
            pattern: suggestion.pattern,
            categoryId: choices[suggestion.pattern].categoryId,
            direction: suggestion.direction,
          })),
        }),
      })

      if (!res.ok) {
        notify("Les règles n'ont pas pu être créées", { type: 'error' })

        return
      }

      const result = (await res.json()) as {
        created: number
        categorized: number
      }
      notify(`${result.created} règle(s) créée(s), ${result.categorized} opération(s) rangée(s).`, {
        type: 'info',
      })
      await load()
    } catch {
      notify("Les règles n'ont pas pu être créées", { type: 'error' })
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <LinearProgress />
  }

  if (suggestions.length === 0) {
    return (
      <Card>
        <CardContent>
          <Placeholder
            title="Rien à proposer pour l'instant"
            description="Les suggestions viennent des opérations déjà importées : un commerçant qui revient au moins deux fois et qu'aucune règle ne couvre encore."
          />
        </CardContent>
      </Card>
    )
  }

  return (
    <Card>
      <CardContent>
        <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 0.5 }}>
          Règles déduites de vos opérations
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Chaque ligne est un commerçant qui revient dans vos relevés. Vérifiez la catégorie,
          décochez ce que vous ne voulez pas, et les règles retenues seront appliquées à
          l'historique dans la foulée.
        </Typography>

        {kept.length === 0 && (
          <Alert severity="info" sx={{ mb: 2 }}>
            Choisissez une catégorie pour au moins une ligne.
          </Alert>
        )}

        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell padding="checkbox" />
                <TableCell>Commerçant</TableCell>
                <TableCell align="right">Fois</TableCell>
                <TableCell align="right">Total</TableCell>
                <TableCell sx={{ minWidth: 200 }}>Catégorie</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {suggestions.map((suggestion) => (
                <TableRow key={suggestion.pattern} hover>
                  <TableCell padding="checkbox">
                    <Checkbox
                      checked={choices[suggestion.pattern]?.selected ?? false}
                      onChange={(e) =>
                        update(suggestion.pattern, {
                          selected: e.target.checked,
                        })
                      }
                    />
                  </TableCell>
                  <TableCell>
                    <Tooltip title={suggestion.samples.join(' · ')} placement="top-start">
                      <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                        {suggestion.pattern}
                      </Typography>
                    </Tooltip>
                  </TableCell>
                  <TableCell align="right">{suggestion.occurrences}</TableCell>
                  <TableCell align="right" sx={{ minWidth: 110 }}>
                    <Amount cents={suggestion.totalCents} signed />
                  </TableCell>
                  <TableCell>
                    <TextField
                      select
                      size="small"
                      fullWidth
                      value={choices[suggestion.pattern]?.categoryId ?? ''}
                      onChange={(e) =>
                        update(suggestion.pattern, {
                          categoryId: e.target.value,
                          selected: true,
                        })
                      }
                    >
                      <MenuItem value="">
                        <em>À choisir</em>
                      </MenuItem>
                      {(categories ?? []).map((category) => (
                        <MenuItem key={category.id as string} value={idOf(category.id as string)}>
                          {category.name as string}
                        </MenuItem>
                      ))}
                    </TextField>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>

        <Stack direction="row" justifyContent="flex-end" sx={{ mt: 2 }}>
          <Button variant="contained" onClick={onCreate} disabled={kept.length === 0 || saving}>
            {saving ? 'Création…' : `Créer ${kept.length} règle(s)`}
          </Button>
        </Stack>
      </CardContent>
    </Card>
  )
}
