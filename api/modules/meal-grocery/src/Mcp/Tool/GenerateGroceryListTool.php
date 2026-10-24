<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Mcp\Tool;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\MealGrocery\Message\GenerateGroceryListCommand;
use Maggie\MealGrocery\Service\GroceryGeneration;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'generate_grocery_list', description: 'Top up the grocery list with the recurring items that are due again; running it twice changes nothing. Meals add nothing by themselves: mealsToChoose names the meals of the date range whose ingredients the owner has not chosen yet (id, date, slot, summary) — offer to choose them with manage_meal_groceries (preview, then add). Date format: YYYY-MM-DD.')]
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

            /** @var GroceryGeneration $generation */
            $generation = $envelope->last(HandledStamp::class)->getResult();
            $list = $generation->list;

            $storeGroups = [];
            foreach ($list->getItems() as $item) {
                $store = $item->getStore();
                $storeKey = null !== $store ? (string) $store->getId() : '__unassigned__';

                if (!isset($storeGroups[$storeKey])) {
                    $storeGroups[$storeKey] = [
                        'storeId' => null !== $store ? (string) $store->getId() : null,
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
                    'storeGroups' => $storeGroups,
                    'totalItems' => $list->getItems()->count(),
                ],
                'mealsToChoose' => array_map(static fn (Meal $meal) => [
                    'id' => (string) $meal->getId(),
                    'date' => $meal->getDate()?->format('Y-m-d'),
                    'slot' => $meal->getSlot()->value,
                    'summary' => $meal->getSummary(),
                ], $generation->mealsToChoose),
            ], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
