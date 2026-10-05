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

#[McpTool(name: 'manage_meals', description: 'List, plan, update, or delete meals. A meal is a day and a slot, never a time: date, fromDate and toDate are YYYY-MM-DD with no hour, slot is lunch or dinner, and recipeIds is a comma-separated list. List needs fromDate and toDate.')]
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
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function list(?string $fromDate, ?string $toDate): string
    {
        if (null === $fromDate || null === $toDate) {
            return json_encode(['error' => 'fromDate and toDate are required for list.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        // Both ends are days, and the range includes them: a meal is a day, so
        // there is no end-of-day instant to reach for here (MAG-251).
        //
        // Lenient, where `create` and `update` are strict: a range is a window
        // to read, so a model that sends a full timestamp — which it does, see
        // GenerateGroceryListHandler — gets the week it asked for rather than
        // an error. Writing a meal on a day nobody named is the mistake worth
        // refusing; reading one is not.
        $meals = $this->mealRepository->findByDateRangeForUser(
            $user,
            $this->dayOf($fromDate),
            $this->dayOf($toDate),
        );

        return json_encode([
            'meals' => array_map(fn (Meal $m) => $this->serialize($m), $meals),
            'count' => count($meals),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $date, ?string $slot, ?string $recipeIds): string
    {
        if (null === $date || null === $slot) {
            return json_encode(['error' => 'date and slot are required for create.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new CreateMealCommand(
            date: $date,
            slot: $slot,
            recipeIds: $this->parseRecipeIds($recipeIds) ?? [],
            userId: (string) $this->userContext->requireUser()->getId(),
        ));

        /** @var Meal $meal */
        $meal = $envelope->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'meal' => $this->serialize($meal)], JSON_THROW_ON_ERROR);
    }

    private function update(?string $mealId, ?string $date, ?string $slot, ?string $recipeIds): string
    {
        if (null === $mealId) {
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
        if (null === $mealId) {
            return json_encode(['error' => 'mealId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteMealCommand(mealId: $mealId));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /**
     * The day an end of a range falls on, read in Paris — a bare day, but also
     * a full timestamp, of which only the day is kept.
     *
     * @throws \DomainException when the string is not a date at all
     */
    private function dayOf(string $date): \DateTimeImmutable
    {
        try {
            $read = new \DateTimeImmutable($date, new \DateTimeZone('Europe/Paris'));
        } catch (\Exception $e) {
            throw new \DomainException("Not a date: \"{$date}\". Use YYYY-MM-DD.", 0, $e);
        }

        return Meal::dayFromString($read->format('Y-m-d'));
    }

    /** @return string[]|null */
    private function parseRecipeIds(?string $recipeIds): ?array
    {
        if (null === $recipeIds) {
            return null;
        }

        return '' !== $recipeIds ? array_map('trim', explode(',', $recipeIds)) : [];
    }

    /** @return array<string, mixed> */
    private function serialize(Meal $meal): array
    {
        return [
            'id' => (string) $meal->getId(),
            'date' => $meal->getDate()?->format('Y-m-d'),
            'slot' => $meal->getSlot()->value,
            'summary' => $meal->getSummary(),
            'recipes' => $meal->getRecipes()->map(fn ($r) => [
                'id' => (string) $r->getId(),
                'name' => $r->getName(),
            ])->toArray(),
        ];
    }
}
