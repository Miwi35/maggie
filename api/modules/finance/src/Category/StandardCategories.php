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
 */
final class StandardCategories
{
    /**
     * @return list<array{
     *     name: string,
     *     obligation: ObligationFlag,
     *     color: string,
     *     icon: string,
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
                'name' => 'Imprévus',
                'obligation' => ObligationFlag::Mandatory,
                'color' => '#EF5350',
                'icon' => 'error-outline',
            ],
        ];
    }
}
