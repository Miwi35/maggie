import frenchMessages from 'ra-language-french'

/**
 * Resource names, so react-admin stops falling back to the API's own wording
 * ("Pas encore de Envelopes."). Each entry is singular then plural, which is
 * what polyglot expects either side of the `||||`.
 */
const resources = {
  accounts: { name: 'Compte |||| Comptes' },
  categories: { name: 'Catégorie |||| Catégories' },
  transactions: { name: 'Opération |||| Opérations' },
  envelopes: { name: 'Enveloppe |||| Enveloppes' },
  categorization_rules: { name: 'Règle |||| Règles' },
  loans: { name: 'Prêt |||| Prêts' },
  products: { name: 'Produit |||| Produits' },
  stores: { name: 'Magasin |||| Magasins' },
  grocery_items: { name: 'Article |||| Articles' },
  recurring_grocery_items: { name: 'Article récurrent |||| Articles récurrents' },
  recipes: { name: 'Recette |||| Recettes' },
  meals: { name: 'Repas |||| Repas' },
  ingredients: { name: 'Ingrédient |||| Ingrédients' },
  agendas: { name: 'Agenda |||| Agendas' },
  events: { name: 'Événement |||| Événements' },
  tasks: { name: 'Tâche |||| Tâches' },
}

export const messages = {
  ...frenchMessages,
  ra: {
    ...frenchMessages.ra,
    page: {
      ...frenchMessages.ra.page,
      // The default weaves the resource name into the sentence, which reads
      // badly in French whatever the name ("Pas encore de Enveloppes").
      empty: 'Rien ici pour le moment.',
      invite: 'Voulez-vous en ajouter ?',
    },
  },
  resources,
}
