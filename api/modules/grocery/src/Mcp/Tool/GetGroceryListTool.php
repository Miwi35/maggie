<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Repository\GroceryListRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_grocery_list', description: 'Get the grocery list with items grouped by store in visit order. By default hides deferred items (buyAfter in the future) from the store groups and lists them, with their date, in `later`. Set includeDeferred=true to see them in the store groups instead.')]
class GetGroceryListTool
{
    public function __construct(
        private readonly GroceryListRepository $groceryListRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(bool $includeDeferred = false): string
    {
        $user = $this->userContext->getUser();

        if (null === $user) {
            return json_encode(['error' => MissingMcpUserException::MESSAGE], JSON_THROW_ON_ERROR);
        }

        $list = $this->groceryListRepository->findOrCreateForUser($user);

        $today = new \DateTimeImmutable('today');
        $storeGroups = [];
        $totalItems = 0;
        $checkedItems = 0;
        $deferredCount = 0;
        $later = [];

        foreach ($list->getItems() as $item) {
            $buyAfter = $item->getBuyAfter();

            // Count deferred
            if (null !== $buyAfter && $buyAfter > $today) {
                ++$deferredCount;
                if (!$includeDeferred) {
                    $later[] = [
                        'id' => (string) $item->getId(),
                        'label' => $item->getLabel(),
                        'quantity' => $item->getQuantity(),
                        'unit' => $item->getUnit()?->value,
                        'buyAfter' => $buyAfter->format('Y-m-d'),
                    ];

                    continue;
                }
            }

            ++$totalItems;
            if ($item->isChecked()) {
                ++$checkedItems;
            }

            $store = $item->getStore();
            $storeKey = null !== $store ? (string) $store->getId() : '__unassigned__';

            if (!isset($storeGroups[$storeKey])) {
                $storeGroups[$storeKey] = [
                    'storeId' => null !== $store ? (string) $store->getId() : null,
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
                'position' => $item->getPosition(),
            ];

            if (null !== $buyAfter) {
                $itemData['buyAfter'] = $buyAfter->format('Y-m-d');
            }

            $storeGroups[$storeKey]['items'][] = $itemData;
        }

        usort($later, fn (array $a, array $b) => [$a['buyAfter'], $a['label']] <=> [$b['buyAfter'], $b['label']]);

        // Sort items within each group by position
        foreach ($storeGroups as &$group) {
            usort($group['items'], fn (array $a, array $b) => $a['position'] <=> $b['position']);
        }
        unset($group);

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
                'storeGroups' => $storeGroups,
                'totalItems' => $totalItems,
                'checkedItems' => $checkedItems,
                'deferredCount' => $deferredCount,
                'later' => $later,
            ],
        ], JSON_THROW_ON_ERROR);
    }
}
