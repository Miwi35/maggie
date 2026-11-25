export interface RecipeNeed {
  name: string
  mealName: string
  day: 'today' | 'tomorrow'
  slot: 'lunch' | 'dinner'
}

const whenLabel = ({ day, slot }: RecipeNeed): string => {
  if (day === 'tomorrow') return 'demain'

  return slot === 'dinner' ? 'ce soir' : 'ce midi'
}

const lowerFirst = (text: string): string => text.charAt(0).toLocaleLowerCase('fr') + text.slice(1)

/** « ce soir · pâtes », or the moment alone when the meal has no name. */
export const recipeNeedWhen = (need: RecipeNeed): string =>
  need.mealName ? `${whenLabel(need)} · ${lowerFirst(need.mealName)}` : whenLabel(need)
