<?php

declare(strict_types=1);

namespace Maggie\Finance\Category;

use Maggie\Finance\Enum\ObligationFlag;

/**
 * The categories a budget starts from.
 *
 * A fresh account with no categories cannot classify anything, and asking
 * someone to invent a taxonomy before their first import is how a budget gets
 * abandoned on day one. These are a starting point, not a standard: they are
 * created as ordinary categories, free to rename, recolour or delete.
 *
 * Only subscriptions carry children, because that is the one heading where the
 * total says nothing useful — knowing water from electricity is the point.
 *
 * A few carry a stable `key`, for what must recognise them without guessing
 * from a name the owner may change: the salary the month is judged on, and the
 * bonuses kept out of it.
 */
final class StandardCategories
{
    public const string SALARY = 'salary';
    public const string BONUS = 'bonus';

    /**
     * @return list<array{
     *     name: string,
     *     obligation: ObligationFlag,
     *     color: string,
     *     icon: string,
     *     key?: string,
     *     children?: list<array{name: string, icon: string}>
     * }>
     */
    public static function all(): array
    {
        return [
            [
                'name' => 'Logement',
                'obligation' => ObligationFlag::Mandatory,
                'color' => '#5C6BC0',
                'icon' => 'home',
            ],
            [
                'name' => 'Nourriture',
                'obligation' => ObligationFlag::Mandatory,
                'color' => '#66BB6A',
                'icon' => 'shopping-cart',
            ],
            [
                'name' => 'Abonnements',
                'obligation' => ObligationFlag::Mandatory,
                'color' => '#26A69A',
                'icon' => 'repeat',
                'children' => [
                    ['name' => 'Eau', 'icon' => 'water-drop'],
                    ['name' => 'Électricité & gaz', 'icon' => 'bolt'],
                    ['name' => 'Internet & téléphone', 'icon' => 'wifi'],
                    ['name' => 'TV & streaming', 'icon' => 'tv'],
                    ['name' => 'Services en ligne', 'icon' => 'cloud'],
                ],
            ],
            [
                'name' => 'Loisirs',
                'obligation' => ObligationFlag::Optional,
                'color' => '#FFA726',
                'icon' => 'sports-esports',
            ],
            [
                'name' => 'Placements',
                'obligation' => ObligationFlag::Investment,
                'color' => '#7E57C2',
                'icon' => 'trending-up',
            ],
            [
                'name' => 'Essence',
                'obligation' => ObligationFlag::Mandatory,
                'color' => '#8D6E63',
                'icon' => 'local-gas-station',
            ],
            [
                'name' => 'Prêts',
                'obligation' => ObligationFlag::Debt,
                'color' => '#78909C',
                'icon' => 'account-balance',
            ],
            [
                'name' => 'Imprévus',
                'obligation' => ObligationFlag::Mandatory,
                'color' => '#EF5350',
                'icon' => 'error-outline',
            ],

            // Money coming in is classified too: a month is a balance, and
            // "where did it go" only means something next to "what came in".
            [
                'name' => 'Salaire',
                'obligation' => ObligationFlag::Income,
                'color' => '#43A047',
                'icon' => 'payments',
                'key' => self::SALARY,
            ],
            [
                'name' => 'Primes',
                'obligation' => ObligationFlag::Income,
                'color' => '#43A047',
                'icon' => 'star',
                'key' => self::BONUS,
            ],
            [
                'name' => 'Aides & allocations',
                'obligation' => ObligationFlag::Income,
                'color' => '#43A047',
                'icon' => 'volunteer-activism',
            ],
            [
                'name' => 'Remboursements',
                'obligation' => ObligationFlag::Income,
                'color' => '#43A047',
                'icon' => 'undo',
            ],
            [
                'name' => 'Revenus exceptionnels',
                'obligation' => ObligationFlag::Income,
                'color' => '#43A047',
                'icon' => 'redeem',
            ],
        ];
    }

    /** @return array<string, string> key => the name the standard category is laid down with */
    public static function keyed(): array
    {
        $names = [];
        foreach (self::all() as $definition) {
            if (isset($definition['key'])) {
                $names[$definition['key']] = $definition['name'];
            }
        }

        return $names;
    }
}
