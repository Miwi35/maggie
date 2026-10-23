<?php

declare(strict_types=1);

namespace Maggie\Grocery\Home;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Service\RecipeNeeds;
use Maggie\Core\Entity\User;
use Maggie\Core\Home\HomeCardProviderInterface;
use Maggie\Core\Home\HomeCardWatcherInterface;
use Maggie\Core\Home\HomeDay;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Symfony\Component\Clock\ClockInterface;

/**
 * The grocery tile of the home page (MAG-563): the open list in a compact
 * form — a few lines per store, in the order of the way through the store —
 * and, above it, what the meals of today and tomorrow need and the owner is
 * low on. The list does not move with « Aujourd'hui · Demain ».
 */
class GroceryHomeCard implements HomeCardProviderInterface, HomeCardWatcherInterface
{
    /** Lines shown per store; the rest is told as a count. */
    public const ITEMS_PER_STORE = 5;

    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly RecipeNeeds $recipeNeeds,
        private readonly ClockInterface $clock,
    ) {
    }

    public function module(): string
    {
        return 'grocery';
    }

    public function watchedEntities(): array
    {
        return [GroceryItem::class, GroceryList::class, Store::class, Product::class, Meal::class, Recipe::class];
    }

    public function state(User $user, HomeDay $day): array
    {
        $today = HomeDay::Today->date($this->clock);
        $items = $this->openItems($user, $today);

        $groups = [];
        foreach ($items as $item) {
            $store = $item->getStore();
            $key = null === $store ? '' : (string) $store->getId();
            $groups[$key] ??= ['store' => $store, 'items' => []];
            $groups[$key]['items'][] = $item;
        }

        uasort($groups, static fn (array $a, array $b): int => ($a['store']?->getVisitOrder() ?? PHP_INT_MAX) <=> ($b['store']?->getVisitOrder() ?? PHP_INT_MAX));

        $stores = [];
        foreach ($groups as $group) {
            $lines = $group['items'];
            usort($lines, self::byAisle(...));

            $stores[] = [
                'id' => null === $group['store'] ? null : (string) $group['store']->getId(),
                'name' => $group['store']?->getName(),
                'count' => \count($lines),
                'items' => array_map(self::line(...), \array_slice($lines, 0, self::ITEMS_PER_STORE)),
                'more' => max(0, \count($lines) - self::ITEMS_PER_STORE),
            ];
        }

        return [
            'count' => \count($items),
            'forRecipes' => array_map(
                static fn (array $need): array => [
                    'name' => $need['name'],
                    'day' => $need['date'] === $today->format('Y-m-d') ? HomeDay::Today->value : HomeDay::Tomorrow->value,
                    'slot' => $need['slot'],
                ],
                $this->recipeNeeds->runningOut($user, $today),
            ),
            'stores' => $stores,
        ];
    }

    /** @return list<GroceryItem> */
    private function openItems(User $user, \DateTimeImmutable $today): array
    {
        /** @var list<GroceryItem> $items */
        $items = $this->em->getRepository(GroceryItem::class)->createQueryBuilder('i')
            ->join('i.groceryList', 'l')
            ->leftJoin('i.product', 'p')->addSelect('p')
            ->leftJoin('i.store', 's')->addSelect('s')
            ->where('l.user = :user')
            ->andWhere('i.checked = false')
            ->andWhere('i.buyAfter IS NULL OR i.buyAfter <= :today')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('today', $today, 'date_immutable')
            ->getQuery()
            ->getResult();

        return $items;
    }

    /** Aisle by aisle, then by the line's own position; « Sans rayon » last. */
    private static function byAisle(GroceryItem $a, GroceryItem $b): int
    {
        $aisleA = $a->getAisle();
        $aisleB = $b->getAisle();

        return [null === $aisleA ? 1 : 0, $aisleA['position'] ?? 0, $aisleA['name'] ?? '', $a->getPosition()]
            <=> [null === $aisleB ? 1 : 0, $aisleB['position'] ?? 0, $aisleB['name'] ?? '', $b->getPosition()];
    }

    /** @return array{id: string, name: string, quantity: ?float, unit: ?string, needed: ?list<array{quantity: float, unit: ?string}>} */
    private static function line(GroceryItem $item): array
    {
        // A packaged line is counted in packagings: that count is what to buy, not the need behind it.
        $packaged = null !== $item->getProduct()?->getPackagingUnit();

        return [
            'id' => (string) $item->getId(),
            'name' => $item->getLabel(),
            'quantity' => $item->getQuantity(),
            'unit' => $item->getUnit()?->value,
            'needed' => $packaged ? null : $item->getNeeded(),
        ];
    }
}
