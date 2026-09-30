<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Repository\RecurringGroceryItemRepository;

class GroceryGenerationService
{
    public function __construct(
        private readonly MealRepository $mealRepository,
        private readonly RecurringGroceryItemRepository $recurringGroceryItemRepository,
    ) {
    }

    /**
     * @return GroceryItem[]
     */
    public function generate(User $user, \DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        $items = [];

        // 1. Aggregate from meals in date range
        $meals = $this->mealRepository->findByDateRangeForUser($user, $from, $to);
        $aggregated = []; // key = productId:unit, value = [product, quantity, unit]

        foreach ($meals as $meal) {
            foreach ($meal->getRecipes() as $recipe) {
                foreach ($recipe->getIngredients() as $ri) {
                    $product = $ri->getIngredient();
                    $unit = $ri->getUnit();
                    $key = (string) $product->getId().':'.$unit->value;

                    if (isset($aggregated[$key])) {
                        $aggregated[$key]['quantity'] += $ri->getQuantity();
                    } else {
                        $aggregated[$key] = [
                            'product' => $product,
                            'quantity' => $ri->getQuantity(),
                            'unit' => $unit,
                        ];
                    }
                }
            }
        }

        foreach ($aggregated as $data) {
            $item = new GroceryItem();
            $item->setProduct($data['product']);
            $item->setQuantity($data['quantity']);
            $item->setUnit($data['unit']);
            $item->setSource(GroceryItemSource::Recipe);
            $item->setStore($data['product']->getPreferredStore());
            $items[] = $item;
        }

        // 2. Add recurring grocery items
        $recurringItems = $this->recurringGroceryItemRepository->findByUser($user);
        foreach ($recurringItems as $recurring) {
            $item = new GroceryItem();
            $item->setProduct($recurring->getProduct());
            $item->setCustomLabel($recurring->getCustomLabel());
            $item->setQuantity($recurring->getQuantity());
            $item->setUnit($recurring->getUnit());
            $item->setSource(GroceryItemSource::Recurring);
            $product = $recurring->getProduct();
            if (null !== $product) {
                $item->setStore($product->getPreferredStore());
            }
            $items[] = $item;
        }

        return $items;
    }
}
