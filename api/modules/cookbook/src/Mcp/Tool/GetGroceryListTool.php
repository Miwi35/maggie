<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Repository\GroceryListRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_grocery_list', description: 'Get the active grocery list or a specific list by ID. Returns all items with checked state.')]
class GetGroceryListTool
{
    public function __construct(
        private readonly GroceryListRepository $groceryListRepository,
    ) {
    }

    public function __invoke(?string $groceryListId = null): string
    {
        $list = $groceryListId !== null
            ? $this->groceryListRepository->find($groceryListId)
            : $this->groceryListRepository->findActive();

        if ($list === null) {
            return json_encode(['error' => 'No grocery list found.'], JSON_THROW_ON_ERROR);
        }

        $items = [];
        foreach ($list->getItems() as $item) {
            $items[] = [
                'id' => (string) $item->getId(),
                'label' => $item->getLabel(),
                'quantity' => $item->getQuantity(),
                'unit' => $item->getUnit()?->value,
                'source' => $item->getSource()->value,
                'checked' => $item->isChecked(),
            ];
        }

        return json_encode([
            'groceryList' => [
                'id' => (string) $list->getId(),
                'weekStart' => $list->getWeekStart()->format('Y-m-d'),
                'status' => $list->getStatus()->value,
                'items' => $items,
            ],
        ], JSON_THROW_ON_ERROR);
    }
}
