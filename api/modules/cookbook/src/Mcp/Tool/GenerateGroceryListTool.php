<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\GroceryList;
use Maggie\Cookbook\Message\GenerateGroceryListCommand;
use Maggie\Core\Repository\UserRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'generate_grocery_list', description: 'Generate a grocery list from planned meals in a date range plus recurring items. Date format: YYYY-MM-DD.')]
class GenerateGroceryListTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(
        string $fromDate,
        string $toDate,
    ): string {
        try {
            $users = $this->userRepository->findAll();
            $user = $users[0] ?? throw new \DomainException('No user found.');

            $envelope = $this->bus->dispatch(new GenerateGroceryListCommand(
                userId: (string) $user->getId(),
                fromDate: $fromDate,
                toDate: $toDate,
            ));

            /** @var GroceryList $list */
            $list = $envelope->last(HandledStamp::class)->getResult();

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
                'success' => true,
                'groceryList' => [
                    'id' => (string) $list->getId(),
                    'weekStart' => $list->getWeekStart()->format('Y-m-d'),
                    'status' => $list->getStatus()->value,
                    'items' => $items,
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
