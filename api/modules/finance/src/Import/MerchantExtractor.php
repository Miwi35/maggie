<?php

declare(strict_types=1);

namespace Maggie\Finance\Import;

/**
 * Digs the merchant out of a bank label.
 *
 * French banks wrap the only useful part — who was paid — in operation
 * wording, a card number, a date, and a trail of contract references. Two
 * visits to the same shop therefore never produce the same label, which is
 * exactly what makes an untreated label useless as a rule pattern.
 *
 * This keeps what survives that stripping, and nothing else.
 */
final class MerchantExtractor
{
    /**
     * Operations whose own wording is the identity.
     *
     * A cash withdrawal is the clear case: "CC BEST 1" is the machine, and
     * nobody recognises their own bank's ATM codes. What the line means is
     * that money left as cash — and that is true of every withdrawal, whatever
     * machine it came out of. The key is spelled as the statement spells it,
     * so it still matches the labels it came from.
     */
    private const SELF_NAMING = [
        '/^RETRAIT AU DISTRIBUTEUR\b/iu' => 'RETRAIT AU DISTRIBUTEUR',
        '/^RETRAIT D\'ESPECES\b/iu' => "RETRAIT D'ESPECES",
        '/^REMBOURSEMENT DE PRET\b/iu' => 'REMBOURSEMENT DE PRET',
    ];

    /** Operation wording banks put in front of the merchant. */
    private const PREFIXES = [
        '/^PAIEMENT PAR CARTE\s*(X\d+\s*)?/iu',
        '/^PRELEVEMENT\s+(\d+\s+)?/iu',
        '/^REJET PRLV\s+/iu',
        '/^REJET VIREMENT(\s+WEB)?\s+/iu',
        '/^VIREMENT EN VOTRE FAVEUR\s+(VIR INST\s+)?(DE\s+)?/iu',
        '/^VIREMENT EMIS(\s+WEB)?\s+/iu',
        '/^VIREMENT\s+(INSTANTANE\s+)?/iu',
        '/^ACHAT CB\s+/iu',
        '/^COTISATION\s+/iu',
        '/^FACTURE CARTE\s+/iu',
        '/^AVOIR\s+/iu',
    ];

    /** Payment intermediaries that sit between the card and the shop. */
    private const INTERMEDIARIES = '/\b(SUMUP|SNP|MP|UEP|MOLLIE|ZTL|PAYPAL|IZ|SQ|STRIPE)\s*\*\s*/iu';

    /**
     * Where the merchant stops and the bank's bookkeeping starts. Everything
     * from the first of these onwards is reference, not identity.
     */
    private const NOISE = [
        '/\s+\d{2}\/\d{2}(\/\d{2,4})?\b.*$/u',          // trailing date
        '/\s+\d{1,2}H\d{2}\b.*$/iu',                     // time of a withdrawal
        '/\s+-?ECHEANCE\b.*$/iu',
        '/\s+NUMERO DE (CLIENT|COMPTE)\b.*$/iu',
        '/\s+(POLICY NUMBER|REF|RUM|MANDAT|ID)\s*:?\s*\S+.*$/iu',
        '/\s+FR\d{2}[A-Z0-9]{6,}.*$/u',                  // creditor identifier
        '/\s+[A-Z0-9]*\d{6,}[A-Z0-9]*.*$/u',             // long reference number
    ];

    /** Enough words to tell two merchants apart, few enough to stay a pattern. */
    private const MAX_WORDS = 4;

    /**
     * The merchant as a person would name it, or null when the label carries
     * no recognisable one — an empty pattern would match everything.
     */
    public static function extract(string $label): ?string
    {
        $value = preg_replace('/\s+/u', ' ', trim($label)) ?? $label;

        foreach (self::SELF_NAMING as $pattern => $name) {
            if (1 === preg_match($pattern, $value)) {
                return $name;
            }
        }

        foreach (self::PREFIXES as $prefix) {
            $value = preg_replace($prefix, '', $value) ?? $value;
        }

        $value = preg_replace(self::INTERMEDIARIES, '', $value) ?? $value;

        foreach (self::NOISE as $noise) {
            $value = preg_replace($noise, '', $value) ?? $value;
        }

        $value = trim($value, " \t\n\r\0\x0B-*/,.");

        if ('' === $value) {
            return null;
        }

        $words = array_slice(preg_split('/\s+/u', $value) ?: [], 0, self::MAX_WORDS);
        $merchant = trim(implode(' ', $words));

        // A pattern of one or two characters would claim half the statement,
        // and a bare card number names no one.
        if (mb_strlen($merchant) < 3 || 1 === preg_match('/^X?\d+$/', $merchant)) {
            return null;
        }

        return $merchant;
    }

    /** Two spellings of the same shop group together under this. */
    public static function key(string $merchant): string
    {
        $folded = mb_strtolower($merchant);
        $folded = strtr($folded, [
            'à' => 'a', 'â' => 'a', 'ä' => 'a', 'é' => 'e', 'è' => 'e', 'ê' => 'e', 'ë' => 'e',
            'î' => 'i', 'ï' => 'i', 'ô' => 'o', 'ö' => 'o', 'ù' => 'u', 'û' => 'u', 'ü' => 'u', 'ç' => 'c',
        ]);

        return trim(preg_replace('/[^a-z0-9 ]/u', '', $folded) ?? $folded);
    }
}
