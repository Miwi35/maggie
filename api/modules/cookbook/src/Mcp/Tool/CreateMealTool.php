<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Message\CreateMealCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'create_meal', description: 'Plan a meal for a specific date and slot. Date format: YYYY-MM-DD. Slot: lunch or dinner. Provide recipe IDs as comma-separated string.')]
class CreateMealTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(
        string $date,
        string $slot,
        string $recipeIds = '',
    ): string {
        try {
            $ids = $recipeIds !== '' ? array_map('trim', explode(',', $recipeIds)) : [];

            $envelope = $this->bus->dispatch(new CreateMealCommand(
                date: $date,
                slot: $slot,
                recipeIds: $ids,
            ));

            /** @var Meal $meal */
            $meal = $envelope->last(HandledStamp::class)->getResult();

            return json_encode([
                'success' => true,
                'meal' => [
                    'id' => (string) $meal->getId(),
                    'summary' => $meal->getSummary(),
                    'date' => $meal->getStartAt()->format('Y-m-d'),
                    'slot' => $meal->getSlot()->value,
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
