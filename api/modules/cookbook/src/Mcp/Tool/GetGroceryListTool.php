<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Repository\GroceryListRepository;
use Maggie\Core\Repository\UserRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_grocery_list', description: 'Get the grocery list with items grouped by store in visit order. By default hides deferred items (buyAfter in the future). Set includeDeferred=true to see all items.')]
class GetGroceryListTool
{
    public function __construct(
        private readonly GroceryListRepository $groceryListRepository,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(bool $includeDeferred = false): string
    {
        $users = $this->userRepository->findAll();
        $user = $users[0] ?? null;

        if ($user === null) {
            return json_encode(['error' => 'No user found.'], JSON_THROW_ON_ERROR);
        }

        $list = $this->groceryListRepository->findOrCreateForUser($user);

        $today = new \DateTimeImmutable('today');
        $storeGroups = [];
        $totalItems = 0;
        $checkedItems = 0;
        $deferredCount = 0;

        foreach ($list->getItems() as $item) {
            $buyAfter = $item->getBuyAfter();

            // Count deferred
            if ($buyAfter !== null && $buyAfter > $today) {
                $deferredCount++;
                if (!$includeDeferred) {
                    continue;
                }
            }

            $totalItems++;
            if ($item->isChecked()) {
                $checkedItems++;
            }

            $store = $item->getStore();
            $storeKey = $store !== null ? (string) $store->getId() : '__unassigned__';

            if (!isset($storeGroups[$storeKey])) {
                $storeGroups[$storeKey] = [
                    'storeId' => $store !== null ? (string) $store->getId() : null,
                    'storeName' => $store?->getName() ?? 'Non assigné',
                    'storeDescription' => $store?->getDescription(),
                    'visitOrder' => $store?->getVisitOrder() ?? PHP_INT_MAX,
                    'items' => [],
                ];
            }

            $itemData = [
                'id' => (string) $item->getId(),
                'label' => $item->getLabel(),
                'quantity' => $item->getQuantity(),
                'unit' => $item->getUnit()?->value,
                'source' => $item->getSource()->value,
                'checked' => $item->isChecked(),
            ];

            if ($buyAfter !== null) {
                $itemData['buyAfter'] = $buyAfter->format('Y-m-d');
            }

            $storeGroups[$storeKey]['items'][] = $itemData;
        }

        // Sort by visitOrder
        usort($storeGroups, fn (array $a, array $b) => $a['visitOrder'] <=> $b['visitOrder']);

        // Remove visitOrder from output
        $storeGroups = array_map(function (array $group) {
            unset($group['visitOrder']);

            return $group;
        }, $storeGroups);

        return json_encode([
            'groceryList' => [
                'id' => (string) $list->getId(),
                'storeGroups' => array_values($storeGroups),
                'totalItems' => $totalItems,
                'checkedItems' => $checkedItems,
                'deferredCount' => $deferredCount,
            ],
        ], JSON_THROW_ON_ERROR);
    }
}
