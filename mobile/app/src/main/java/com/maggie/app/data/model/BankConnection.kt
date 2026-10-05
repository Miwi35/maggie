package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A link to a bank, and where it stands.
 *
 * `needsReconnecting` is computed by the API, not here: whether a consent is
 * still usable depends on the server's clock and on the provider's answer, and
 * a phone that is a day off must not decide it on its own.
 */
@Serializable
data class BankConnection(
    val id: String,
    val bankName: String,
    val country: String = "FR",
    val status: String = "pending",
    val consentExpiresAt: String? = null,
    val daysBeforeExpiry: Int? = null,
    val lastSyncedAt: String? = null,
    val needsReconnecting: Boolean = false,
)

@Serializable
data class BankConnectionsResponse(
    val connections: List<BankConnection> = emptyList(),
)

/** Where to send the user for the consent: their bank's own screen. */
@Serializable
data class BankAuthorization(
    val authorizationUrl: String,
    val connectionId: String? = null,
)

@Serializable
data class BankSyncAccountResult(
    val bankName: String? = null,
    val accountName: String? = null,
    val status: String = "",
    val imported: Int = 0,
    val skipped: Int = 0,
    val message: String? = null,
)

@Serializable
data class BankSyncResult(
    val imported: Int = 0,
    val skipped: Int = 0,
    val providerCalls: Int = 0,
    val accounts: List<BankSyncAccountResult> = emptyList(),
)

/** The same wording as the admin's `CONNECTION_STATUS_LABELS`. */
fun bankConnectionStatusLabel(status: String): String = when (status) {
    "pending" -> "En attente"
    "active" -> "Connectée"
    "expired" -> "À reconnecter"
    "revoked" -> "Révoquée"
    else -> status
}

/** Where this link stands, said plainly, and what it asks of the user. */
fun bankConnectionNotice(connection: BankConnection): String? = when {
    connection.status == "pending" ->
        "L'autorisation n'a pas été menée jusqu'au bout. Reprenez la connexion pour l'activer."
    connection.needsReconnecting ->
        "Accès expiré — reconnectez cette banque pour reprendre la synchronisation."
    connection.daysBeforeExpiry == null -> null
    connection.daysBeforeExpiry <= 7 -> "L'accès expire dans ${connection.daysBeforeExpiry} jour(s)."
    else -> null
}

/**
 * A pending journey and an expired consent both end in the same place — back at
 * the bank — so the button is the same; only its wording differs.
 */
fun bankReconnectLabel(connection: BankConnection): String =
    if (connection.status == "pending") "Reprendre" else "Reconnecter"

fun bankConnectionNeedsAction(connection: BankConnection): Boolean =
    connection.status == "pending" || connection.needsReconnecting

private val SYNC_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/**
 * When the bank was last read. An unparseable date is shown as it came rather
 * than hidden: a wrong-looking date is a bug worth seeing.
 */
fun lastSyncLabel(lastSyncedAt: String?, zone: ZoneId = ZoneId.systemDefault()): String {
    if (lastSyncedAt == null) {
        return "Jamais synchronisée"
    }

    val formatted = try {
        SYNC_FORMATTER.format(OffsetDateTime.parse(lastSyncedAt).atZoneSameInstant(zone))
    } catch (_: Exception) {
        lastSyncedAt
    }

    return "Dernière synchronisation : $formatted"
}

/**
 * What a fetch brought back, in one line.
 *
 * A bank that refused is said first: the daily allowance is spent and trying
 * again now would only fail again. An expired consent comes next, because
 * "aucune nouvelle opération" would otherwise read as "tout est à jour" when in
 * fact nothing was even asked.
 */
fun bankSyncSummary(result: BankSyncResult): String {
    result.accounts.firstOrNull { it.status == "rate_limited" }?.let { refused ->
        return refused.message?.takeIf { it.isNotBlank() }
            ?: "La banque a refusé une récupération de plus pour le moment."
    }

    if (result.imported == 0 && result.skipped == 0) {
        result.accounts.firstOrNull { it.status == "needs_reconnecting" }?.let { expired ->
            return expired.message?.takeIf { it.isNotBlank() }
                ?: "L'accès à cette banque a expiré."
        }
    }

    return if (result.imported > 0) {
        "${result.imported} opération(s) importée(s), ${result.skipped} déjà présente(s)."
    } else {
        "Aucune nouvelle opération."
    }
}
