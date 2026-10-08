<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Message\ChooseMealGroceriesCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Service\MealGroceryChoice;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * One tool for the preview and the choice: Maggie always does both in a row,
 * and two tools would cost her one more round trip (MAG-295).
 */
#[McpTool(name: 'manage_meal_groceries', description: 'Choose which ingredients of a planned meal go on the grocery list. preview lists the meal\'s ingredients with the quantity to buy in packagings (toBuy), the product\'s stock state (in_stock, low, out) and suggested=true when it is low or out: tell the owner which ones are running low and ask which to add. add puts only the chosen ones on the list, in packagings, and replaces what the meal had put there before: ingredientIds is a comma-separated list of ingredientId from preview, empty to add nothing.')]
class ManageMealGroceriesTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly MealRepository $mealRepository,
        private readonly MealGroceryChoice $choice,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $action, ?string $mealId = null, ?string $ingredientIds = null): string
    {
        try {
            return match ($action) {
                'preview' => $this->preview($mealId),
                'add' => $this->add($mealId, $ingredientIds),
                default => json_encode(['error' => "Unknown action: {$action}. Use preview or add."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function preview(?string $mealId): string
    {
        if (null === $mealId) {
            return json_encode(['error' => 'mealId is required for preview.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();
        $meal = $this->mealRepository->findOneForUser($mealId, $user)
            ?? throw new \DomainException("Meal not found: {$mealId}");

        return json_encode($this->choice->preview($meal), JSON_THROW_ON_ERROR);
    }

    private function add(?string $mealId, ?string $ingredientIds): string
    {
        if (null === $mealId || null === $ingredientIds) {
            return json_encode(['error' => 'mealId and ingredientIds are required for add.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();
        $meal = $this->mealRepository->findOneForUser($mealId, $user)
            ?? throw new \DomainException("Meal not found: {$mealId}");

        $ids = array_values(array_filter(array_map('trim', explode(',', $ingredientIds)), static fn (string $id) => '' !== $id));

        $this->bus->dispatch(new ChooseMealGroceriesCommand(
            mealId: (string) $meal->getId(),
            userId: (string) $user->getId(),
            chosen: array_fill_keys($ids, null),
        ));

        return json_encode(['success' => true] + $this->choice->preview($meal), JSON_THROW_ON_ERROR);
    }
}
