<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Message\DeleteMealCommand;
use Maggie\Cookbook\Message\UpdateMealCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_meals', description: 'List, plan, update, or delete meals. Dates are YYYY-MM-DD, slot is lunch or dinner, and recipeIds is a comma-separated list. List needs fromDate and toDate.')]
class ManageMealsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly MealRepository $mealRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?string $mealId = null,
        ?string $fromDate = null,
        ?string $toDate = null,
        ?string $date = null,
        ?string $slot = null,
        ?string $recipeIds = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list($fromDate, $toDate),
                'create' => $this->create($date, $slot, $recipeIds),
                'update' => $this->update($mealId, $date, $slot, $recipeIds),
                'delete' => $this->delete($mealId),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, create, update, or delete."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function list(?string $fromDate, ?string $toDate): string
    {
        if ($fromDate === null || $toDate === null) {
            return json_encode(['error' => 'fromDate and toDate are required for list.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $timeZone = new \DateTimeZone('Europe/Paris');
        $meals = $this->mealRepository->findByDateRangeForUser(
            $user,
            new \DateTimeImmutable($fromDate, $timeZone),
            new \DateTimeImmutable($toDate . ' 23:59:59', $timeZone),
        );

        return json_encode([
            'meals' => array_map(fn (Meal $m) => $this->serialize($m), $meals),
            'count' => count($meals),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $date, ?string $slot, ?string $recipeIds): string
    {
        if ($date === null || $slot === null) {
            return json_encode(['error' => 'date and slot are required for create.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new CreateMealCommand(
            date: $date,
            slot: $slot,
            recipeIds: $this->parseRecipeIds($recipeIds) ?? [],
        ));

        /** @var Meal $meal */
        $meal = $envelope->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'meal' => $this->serialize($meal)], JSON_THROW_ON_ERROR);
    }

    private function update(?string $mealId, ?string $date, ?string $slot, ?string $recipeIds): string
    {
        if ($mealId === null) {
            return json_encode(['error' => 'mealId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new UpdateMealCommand(
            mealId: $mealId,
            date: $date,
            slot: $slot,
            recipeIds: $this->parseRecipeIds($recipeIds),
        ));

        /** @var Meal $meal */
        $meal = $envelope->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'meal' => $this->serialize($meal)], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $mealId): string
    {
        if ($mealId === null) {
            return json_encode(['error' => 'mealId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteMealCommand(mealId: $mealId));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /** @return string[]|null */
    private function parseRecipeIds(?string $recipeIds): ?array
    {
        if ($recipeIds === null) {
            return null;
        }

        return $recipeIds !== '' ? array_map('trim', explode(',', $recipeIds)) : [];
    }

    /** @return array<string, mixed> */
    private function serialize(Meal $meal): array
    {
        return [
            'id' => (string) $meal->getId(),
            'date' => $meal->getStartAt()->format('Y-m-d'),
            'slot' => $meal->getSlot()->value,
            'summary' => $meal->getSummary(),
            'recipes' => $meal->getRecipes()->map(fn ($r) => [
                'id' => (string) $r->getId(),
                'name' => $r->getName(),
            ])->toArray(),
        ];
    }
}
