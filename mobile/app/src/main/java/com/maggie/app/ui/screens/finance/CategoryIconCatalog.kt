package com.maggie.app.ui.screens.finance

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import java.text.Normalizer

/** [name] is the value of `Category.icon`. */
internal data class CategoryIconEntry(
    val name: String,
    val label: String,
    val keywords: List<String>,
    val icon: ImageVector,
)

/** What the picker shows for one icon; [icon] is null for a name we do not draw (a generic tag is used). */
internal data class CategoryIconTile(val name: String, val label: String, val icon: ImageVector?)

/**
 * Every icon a category can take, with the French label and search words the picker shows.
 * Same names, labels and keywords as the admin's `categoryIconCatalog.ts`: keep the two identical.
 */
internal object CategoryIconCatalog {
    val entries: List<CategoryIconEntry> = listOf(
        CategoryIconEntry("home", "Maison", listOf("logement", "loyer", "domicile", "immobilier"), Icons.Default.Home),
        CategoryIconEntry("shopping-cart", "Courses", listOf("supermarché", "panier", "alimentation"), Icons.Default.ShoppingCart),
        CategoryIconEntry("repeat", "Abonnement", listOf("récurrent", "prélèvement", "mensuel"), Icons.Default.Repeat),
        CategoryIconEntry("water-drop", "Eau", listOf("facture", "robinet"), Icons.Default.WaterDrop),
        CategoryIconEntry("bolt", "Électricité", listOf("énergie", "courant", "edf"), Icons.Default.Bolt),
        CategoryIconEntry("wifi", "Internet", listOf("box", "fibre", "téléphone"), Icons.Default.Wifi),
        CategoryIconEntry("tv", "Télévision", listOf("télé", "streaming", "redevance", "écran"), Icons.Default.Tv),
        CategoryIconEntry("cloud", "Cloud", listOf("stockage", "hébergement", "logiciel"), Icons.Default.Cloud),
        CategoryIconEntry("sports-esports", "Jeux vidéo", listOf("jeux", "console", "gaming"), Icons.Default.SportsEsports),
        CategoryIconEntry("trending-up", "Investissement", listOf("bourse", "placement", "hausse"), Icons.AutoMirrored.Filled.TrendingUp),
        CategoryIconEntry("local-gas-station", "Carburant", listOf("essence", "plein", "station"), Icons.Default.LocalGasStation),
        CategoryIconEntry("account-balance", "Banque", listOf("frais bancaires", "impôts", "administration"), Icons.Default.AccountBalance),
        CategoryIconEntry("error-outline", "Imprévu", listOf("alerte", "amende", "urgence"), Icons.Default.ErrorOutline),
        CategoryIconEntry("payments", "Salaire", listOf("paie", "revenu", "espèces"), Icons.Default.Payments),
        CategoryIconEntry("star", "Favori", listOf("étoile", "important"), Icons.Default.Star),
        CategoryIconEntry("volunteer-activism", "Dons", listOf("don", "association", "charité"), Icons.Default.VolunteerActivism),
        CategoryIconEntry("undo", "Remboursement", listOf("retour", "annulation"), Icons.AutoMirrored.Filled.Undo),
        CategoryIconEntry("redeem", "Cadeaux", listOf("cadeau", "anniversaire", "fête"), Icons.Default.Redeem),
        CategoryIconEntry("restaurant", "Restaurant", listOf("resto", "repas", "sortie"), Icons.Default.Restaurant),
        CategoryIconEntry("directions-car", "Voiture", listOf("auto", "transport", "garage"), Icons.Default.DirectionsCar),
        CategoryIconEntry("flight", "Voyage", listOf("avion", "vacances"), Icons.Default.Flight),
        CategoryIconEntry("school", "Études", listOf("école", "formation", "scolarité"), Icons.Default.School),
        CategoryIconEntry("favorite", "Cœur", listOf("amour", "plaisir"), Icons.Default.Favorite),
        CategoryIconEntry("fitness-center", "Sport", listOf("salle", "musculation", "santé"), Icons.Default.FitnessCenter),
        CategoryIconEntry("movie", "Cinéma", listOf("film", "culture"), Icons.Default.Movie),
        CategoryIconEntry("savings", "Épargne", listOf("tirelire", "économies", "livret"), Icons.Default.Savings),
    )

    fun find(name: String?): CategoryIconEntry? {
        val wanted = name?.trim()?.lowercase().orEmpty()
        return entries.firstOrNull { it.name == wanted }
    }

    /** Lower case, no accents, « œ » as « oe »: « Épargne » and « epargne » are one word. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("œ", "oe")
            .replace("Œ", "oe")
            .lowercase()
            .trim()

    /** The icons whose label, keywords or name contain the query; all of them for a blank query. */
    fun filter(query: String): List<CategoryIconEntry> {
        val wanted = normalize(query)
        if (wanted.isEmpty()) return entries
        return entries.filter { entry ->
            (listOf(entry.label, entry.name) + entry.keywords).any { normalize(it).contains(wanted) }
        }
    }

    /**
     * What the picker lists for [query]: the matching icons, preceded by [current] when it is
     * a name outside the catalogue (kept, shown selected, never silently lost).
     */
    fun tiles(query: String, current: String?): List<CategoryIconTile> {
        val found = filter(query).map { CategoryIconTile(it.name, it.label, it.icon) }
        val name = current?.trim().orEmpty()
        val unknownShown = name.isNotEmpty() && find(name) == null &&
            (query.isBlank() || normalize(name).contains(normalize(query)))
        return if (unknownShown) listOf(CategoryIconTile(name, name, null)) + found else found
    }
}
