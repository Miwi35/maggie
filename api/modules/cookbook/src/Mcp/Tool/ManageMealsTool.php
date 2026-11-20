<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Calendar\Service\EventReadFilter;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Message\DeleteMealCommand;
use Maggie\Cookbook\Message\UpdateMealCommand;
use Maggie\Cookbook\Port\MealGroceryPreviewInterface;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Service\MealGuests;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_meals', description: 'Actions: list, create, update, delete. A meal is a date (YYYY-MM-DD) and a slot (lunch, dinner); recipeIds comma-separated. list needs fromDate and toDate, or query (title words, tolerant to case, accents, typos; all agendas, next year when no dates). create needs date and slot, returns groceryPreview (ingredients with stock), to choose from with manage_meal_groceries. guests (1 or more) scales recipes and, on update, the grocery list; unset follows the manage_meal_settings default, useDefaultGuests true restores it. update and delete need mealId.')]
class ManageMealsTool
{
    /** How far a search by title looks when the caller gave no window of its own. */
    private const TITLE_SEARCH_DAYS = 365;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly MealRepository $mealRepository,
        private readonly McpUserContext $userContext,
        private readonly MealGroceryPreviewInterface $groceryPreview,
        private readonly MealGuests $mealGuests,
        private readonly EventReadFilter $titleFilter,
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
        ?int $guests = null,
        ?bool $useDefaultGuests = null,
        ?string $query = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list($fromDate, $toDate, $query),
                'create' => $this->create($date, $slot, $recipeIds, Meal::guestsFrom($guests)),
                'update' => $this->update($mealId, $date, $slot, $recipeIds, Meal::guestsFrom($guests), $useDefaultGuests ?? false),
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

    private function list(?string $fromDate, ?string $toDate, ?string $query): string
    {
        $this->titleFilter->assertSearchable($query);
        $searching = $this->titleFilter->hasQuery($query);

        // A name search with no window of its own looks ahead: the meal is still to be eaten.
        if ($searching && null === $fromDate && null === $toDate) {
            $fromDate = (new \DateTimeImmutable('today'))->format('Y-m-d');
            $toDate = (new \DateTimeImmutable('today +'.self::TITLE_SEARCH_DAYS.' days'))->format('Y-m-d');
        }

        if (null === $fromDate || null === $toDate) {
            return json_encode(['error' => 'fromDate and toDate are required for list, unless query is given.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        // Both ends are days, and the range includes them: a meal is a day, so
        // there is no end-of-day instant to reach for here (MAG-251).
        //
        // Lenient, where `create` and `update` are strict — Meal::dayOfString
        // says why, and `generate_grocery_list` reads its own range the same
        // way through it.
        $meals = $this->mealRepository->findByDateRangeForUser(
            $user,
            Meal::dayOfString($fromDate),
            Meal::dayOfString($toDate),
        );
        if ($searching) {
            /** @var Meal[] $meals */
            $meals = $this->titleFilter->keep($meals, null, $query);
        }

        return json_encode([
            'meals' => array_map(fn (Meal $m) => $this->serialize($m), $meals),
            'count' => count($meals),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $date, ?string $slot, ?string $recipeIds, ?int $guests): string
    {
        if (null === $date || null === $slot) {
            return json_encode(['error' => 'date and slot are required for create.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new CreateMealCommand(
            date: $date,
            slot: $slot,
            recipeIds: $this->parseRecipeIds($recipeIds) ?? [],
            userId: (string) $this->userContext->requireUser()->getId(),
            guests: $guests,
        ));

        /** @var Meal $meal */
        $meal = $envelope->last(HandledStamp::class)->getResult();

        // The preview rides along, so Maggie can go straight on to asking which
        // ingredients to buy (manage_meal_groceries add) without another call.
        return json_encode([
            'success' => true,
            'meal' => $this->serialize($meal),
            'groceryPreview' => $this->groceryPreview->preview($meal),
        ], JSON_THROW_ON_ERROR);
    }

    private function update(?string $mealId, ?string $date, ?string $slot, ?string $recipeIds, ?int $guests, bool $useDefaultGuests): string
    {
        if (null === $mealId) {
            return json_encode(['error' => 'mealId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new UpdateMealCommand(
            mealId: $mealId,
            date: $date,
            slot: $slot,
            recipeIds: $this->parseRecipeIds($recipeIds),
            guests: $guests,
            useDefaultGuests: $useDefaultGuests,
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
            'guests' => $this->mealGuests->of($meal),
            'guestsIsDefault' => $meal->getGuestsIsDefault(),
            'recipes' => $meal->getRecipes()->map(fn ($r) => [
                'id' => (string) $r->getId(),
                'name' => $r->getName(),
            ])->toArray(),
        ];
    }
}
