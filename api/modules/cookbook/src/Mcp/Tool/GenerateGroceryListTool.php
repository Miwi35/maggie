<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Cookbook\Message\GenerateGroceryListCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'generate_grocery_list', description: 'Generate grocery items from planned meals in a date range plus recurring items. Items are added to the existing grocery list. Date format: YYYY-MM-DD.')]
class GenerateGroceryListTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $fromDate,
        string $toDate,
    ): string {
        try {
            $user = $this->userContext->requireUser();

            $envelope = $this->bus->dispatch(new GenerateGroceryListCommand(
                userId: (string) $user->getId(),
                fromDate: $fromDate,
                toDate: $toDate,
            ));

            /** @var GroceryList $list */
            $list = $envelope->last(HandledStamp::class)->getResult();

            $storeGroups = [];
            foreach ($list->getItems() as $item) {
                $store = $item->getStore();
                $storeKey = $store !== null ? (string) $store->getId() : '__unassigned__';

                if (!isset($storeGroups[$storeKey])) {
                    $storeGroups[$storeKey] = [
                        'storeId' => $store !== null ? (string) $store->getId() : null,
                        'storeName' => $store?->getName() ?? 'Non assigné',
                        'visitOrder' => $store?->getVisitOrder() ?? PHP_INT_MAX,
                        'items' => [],
                    ];
                }

                $storeGroups[$storeKey]['items'][] = [
                    'id' => (string) $item->getId(),
                    'label' => $item->getLabel(),
                    'quantity' => $item->getQuantity(),
                    'unit' => $item->getUnit()?->value,
                    'source' => $item->getSource()->value,
                    'checked' => $item->isChecked(),
                ];
            }

            usort($storeGroups, fn (array $a, array $b) => $a['visitOrder'] <=> $b['visitOrder']);
            $storeGroups = array_map(function (array $group) {
                unset($group['visitOrder']);

                return $group;
            }, $storeGroups);

            return json_encode([
                'success' => true,
                'groceryList' => [
                    'id' => (string) $list->getId(),
                    'storeGroups' => array_values($storeGroups),
                    'totalItems' => $list->getItems()->count(),
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
