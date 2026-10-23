export interface RecipeNeed {
  name: string
  day: 'today' | 'tomorrow'
  slot: 'lunch' | 'dinner'
}

const whenLabel = ({ day, slot }: RecipeNeed): string => {
  if (day === 'tomorrow') return 'demain'

  return slot === 'dinner' ? 'ce soir' : 'ce midi'
}

const lowerFirst = (text: string): string => text.charAt(0).toLocaleLowerCase('fr') + text.slice(1)

/** « parmesan (ce soir), crème fraîche (demain) » */
export const recipeNeedsLabel = (needs: RecipeNeed[]): string =>
  needs.map((need) => `${lowerFirst(need.name)} (${whenLabel(need)})`).join(', ')
