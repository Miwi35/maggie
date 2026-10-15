export interface CategoryIconEntry {
  /** The value of `Category.icon`. */
  name: string
  label: string
  keywords: string[]
}

/**
 * Every icon a category can take, with the French label and search words the picker shows.
 * Same names, labels and keywords as the mobile `CategoryIconCatalog.kt`: keep the two identical.
 */
export const CATEGORY_ICON_CATALOG: CategoryIconEntry[] = [
  { name: 'home', label: 'Maison', keywords: ['logement', 'loyer', 'domicile', 'immobilier'] },
  { name: 'shopping-cart', label: 'Courses', keywords: ['supermarché', 'panier', 'alimentation'] },
  { name: 'repeat', label: 'Abonnement', keywords: ['récurrent', 'prélèvement', 'mensuel'] },
  { name: 'water-drop', label: 'Eau', keywords: ['facture', 'robinet'] },
  { name: 'bolt', label: 'Électricité', keywords: ['énergie', 'courant', 'edf'] },
  { name: 'wifi', label: 'Internet', keywords: ['box', 'fibre', 'téléphone'] },
  { name: 'tv', label: 'Télévision', keywords: ['télé', 'streaming', 'redevance', 'écran'] },
  { name: 'cloud', label: 'Cloud', keywords: ['stockage', 'hébergement', 'logiciel'] },
  { name: 'sports-esports', label: 'Jeux vidéo', keywords: ['jeux', 'console', 'gaming'] },
  { name: 'trending-up', label: 'Investissement', keywords: ['bourse', 'placement', 'hausse'] },
  { name: 'local-gas-station', label: 'Carburant', keywords: ['essence', 'plein', 'station'] },
  { name: 'account-balance', label: 'Banque', keywords: ['frais bancaires', 'impôts', 'administration'] },
  { name: 'error-outline', label: 'Imprévu', keywords: ['alerte', 'amende', 'urgence'] },
  { name: 'payments', label: 'Salaire', keywords: ['paie', 'revenu', 'espèces'] },
  { name: 'star', label: 'Favori', keywords: ['étoile', 'important'] },
  { name: 'volunteer-activism', label: 'Dons', keywords: ['don', 'association', 'charité'] },
  { name: 'undo', label: 'Remboursement', keywords: ['retour', 'annulation'] },
  { name: 'redeem', label: 'Cadeaux', keywords: ['cadeau', 'anniversaire', 'fête'] },
  { name: 'restaurant', label: 'Restaurant', keywords: ['resto', 'repas', 'sortie'] },
  { name: 'directions-car', label: 'Voiture', keywords: ['auto', 'transport', 'garage'] },
  { name: 'flight', label: 'Voyage', keywords: ['avion', 'vacances'] },
  { name: 'school', label: 'Études', keywords: ['école', 'formation', 'scolarité'] },
  { name: 'favorite', label: 'Cœur', keywords: ['amour', 'plaisir'] },
  { name: 'fitness-center', label: 'Sport', keywords: ['salle', 'musculation', 'santé'] },
  { name: 'movie', label: 'Cinéma', keywords: ['film', 'culture'] },
  { name: 'savings', label: 'Épargne', keywords: ['tirelire', 'économies', 'livret'] },
]

/** Lower case, no accents, « œ » as « oe »: « Épargne » and « epargne » are one word. */
export const normalizeSearch = (text: string): string =>
  text
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/œ/gi, 'oe')
    .toLowerCase()
    .trim()

export const findCategoryIcon = (name: string | null | undefined): CategoryIconEntry | undefined => {
  const wanted = typeof name === 'string' ? name.trim().toLowerCase() : ''

  return CATEGORY_ICON_CATALOG.find((entry) => entry.name === wanted)
}

/** The icons whose label, keywords or name contain the query; all of them for a blank query. */
export const filterCategoryIcons = (query: string): CategoryIconEntry[] => {
  const wanted = normalizeSearch(query)
  if (wanted === '') {
    return CATEGORY_ICON_CATALOG
  }

  return CATEGORY_ICON_CATALOG.filter((entry) =>
    [entry.label, entry.name, ...entry.keywords].some((text) => normalizeSearch(text).includes(wanted)),
  )
}
