<?php

declare(strict_types=1);

namespace Maggie\Finance\Category;

/**
 * Guesses which heading a merchant belongs under.
 *
 * Deliberately small and deliberately cautious: a wrong guess quietly files
 * money in the wrong place, which is worse than no guess at all — an
 * unanswered suggestion just asks the user one question. So only merchants
 * whose business is unambiguous are listed.
 *
 * Order matters. "INTERMARCHE ESSENCE" is fuel, not groceries, so the fuel
 * entries are read before the supermarkets that also sell it.
 */
final class MerchantDictionary
{
    /** @var list<array{needles: list<string>, category: string, creditOnly?: bool}> */
    private const ENTRIES = [
        // Fuel first: supermarket brands run the stations too.
        ['needles' => ['ESSENCE', 'CARBURANT', 'ESS24', 'STATION', 'AVIA', 'ARAL', 'ESSO', 'AGIP',
            'TOTAL ACCESS', 'RELAIS ', 'BP ', 'SHELL'], 'category' => 'Essence'],

        ['needles' => ['ELECTRICITE DE FRANCE', 'EDF', 'ENGIE', 'TOTALENERGIES', 'EKWATEUR', 'ENERCOOP',
            'ODELECTRIC'], 'category' => 'Électricité & gaz'],
        ['needles' => ['VEOLIA', 'SAUR', 'SUEZ', 'EAU DU', 'SYNDICAT DES EAUX'], 'category' => 'Eau'],
        ['needles' => ['ORANGE', 'SFR', 'BOUYGUES TELECOM', 'FREE MOBILE', 'FREE HAUT DEBIT', 'SOSH',
            'RED BY SFR'], 'category' => 'Internet & téléphone'],
        ['needles' => ['NETFLIX', 'SPOTIFY', 'DEEZER', 'DISNEY', 'CANAL+', 'CANALPLUS', 'MAX.COM',
            'PARAMOUNT'], 'category' => 'TV & streaming'],
        ['needles' => ['GOOGLE', 'APPLE.COM', 'ITUNES', 'MICROSOFT', 'ANTHROPIC', 'OPENAI', 'GITHUB',
            'ADOBE', 'DROPBOX', 'OVH', 'SCALEWAY'], 'category' => 'Services en ligne'],

        ['needles' => ['CARREFOUR', 'INTERMARCHE', 'SUPER U', 'HYPER U', 'LECLERC', 'AUCHAN', 'LIDL',
            'ALDI', 'MONOPRIX', 'FRANPRIX', 'CASINO', 'BIOCOOP', 'GRAND FRAIS', 'PICARD', 'NETTO',
            'BOULANGERIE', 'BOUCHERIE', 'PRIMEUR', 'MARCHE '], 'category' => 'Nourriture'],

        ['needles' => ['ASSURANCE HABITATION', 'REDEVANCEDECHETS', 'REDEVANCE DECHETS', 'ORDURES',
            'SYNDIC', 'FONCIER', 'TAXE HABITATION'], 'category' => 'Logement'],

        ['needles' => ['WEEZEVENT', 'FESTIVAL', 'CINEMA', 'UGC', 'PATHE', 'FNAC', 'BILLETWEB',
            'TICKETMASTER', 'DECATHLON'], 'category' => 'Loisirs'],

        // Money in. These only ever read a credit: "SALAIRE" on a debit is a
        // transfer someone sent, not wages arriving.
        ['needles' => ['SALAIRE', 'PAIE ', 'REM. SALAIRE', 'TRAITEMENT', 'SOLDE DE TOUT COMPTE'],
            'category' => 'Salaire', 'creditOnly' => true],
        ['needles' => ['CAF ', 'CAISSE D ALLOCATIONS', 'ALLOCATIONS FAMILIALES', 'POLE EMPLOI',
            'FRANCE TRAVAIL', 'MSA '], 'category' => 'Aides & allocations', 'creditOnly' => true],
        ['needles' => ['CPAM', 'AMELI', 'ASSURANCE MALADIE', 'MUTUELLE', 'HARMONIE MUTUELLE',
            'MGEN', 'REMBOURSEMENT'], 'category' => 'Remboursements', 'creditOnly' => true],
    ];

    /**
     * The heading this merchant belongs under, or null when it is not obvious.
     *
     * The direction is part of the reading: an income heading only ever fits
     * money arriving. A refund from a shop, on the other hand, belongs under
     * the same heading as the purchase it cancels — so expense entries stay
     * indifferent to it.
     */
    public static function categoryFor(string $merchant, bool $isCredit = false): ?string
    {
        $haystack = mb_strtoupper($merchant);

        foreach (self::ENTRIES as $entry) {
            if (($entry['creditOnly'] ?? false) && !$isCredit) {
                continue;
            }

            foreach ($entry['needles'] as $needle) {
                if (str_contains($haystack, $needle)) {
                    return $entry['category'];
                }
            }
        }

        return null;
    }
}
