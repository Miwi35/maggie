<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Repository\GroceryListRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_grocery_list', description: 'Get the grocery list with items grouped by store in visit order. By default hides deferred items (buyAfter in the future) from the store groups and lists them, with their date, in `later`. Set includeDeferred=true to see them in the store groups instead. Each line carries `quantity` and `unit` (a packaging unit such as pack or jar reads « 2 paquets »), `packaging` (what the product is bought in, with its content when known, e.g. a 500 g pack; null when it has none) and `stockState` (in_stock, low or out).')]
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
                        ...$this->productFacts($item),
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
                ...$this->productFacts($item),
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

    /**
     * What Maggie needs to say « 2 paquets » or « en rupture » from a line: the
     * packaging the product is bought in (the line's unit is that packaging when
     * the two agree) and what is left of it at home.
     *
     * @return array{packaging: array{unit: ?string, size: ?float, sizeUnit: ?string}|null, stockState: ?string}
     */
    private function productFacts(GroceryItem $item): array
    {
        $product = $item->getProduct();
        $packagingUnit = $product?->getPackagingUnit();

        return [
            'packaging' => null !== $product && null !== $packagingUnit ? [
                'unit' => $packagingUnit->value,
                'size' => $product->getPackagingSize(),
                'sizeUnit' => $product->getPackagingSizeUnit()?->value,
            ] : null,
            'stockState' => $product?->getStockState()->value,
        ];
    }
}
