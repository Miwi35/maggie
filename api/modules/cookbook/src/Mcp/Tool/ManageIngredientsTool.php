<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Message\CreateIngredientCommand;
use Maggie\Cookbook\Message\DeleteIngredientCommand;
use Maggie\Cookbook\Message\UpdateIngredientCommand;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_ingredients', description: 'Create, update, or delete food ingredients. Categories: produce, dairy, meat, fish, grain, spice, condiment, frozen, beverage, other. Units: g, kg, ml, l, cl, piece, bunch, can, bottle, pack, sachet. Call search_ciqual_foods first for nutrition data and pass the ciqualAlimCode; use search_ingredients to look one up. To empty an optional field on update, list its name in clear (defaultUnit, ciqualAlimCode, kcalPer100g, proteinPer100g, carbsPer100g, fatPer100g).')]
class ManageIngredientsTool
{
    private const CLEARABLE_FIELDS = ['defaultUnit', 'ciqualAlimCode', 'kcalPer100g', 'proteinPer100g', 'carbsPer100g', 'fatPer100g'];

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?string $ingredientId = null,
        ?string $name = null,
        ?string $category = null,
        ?string $defaultUnit = null,
        ?string $ciqualAlimCode = null,
        ?float $kcalPer100g = null,
        ?float $proteinPer100g = null,
        ?float $carbsPer100g = null,
        ?float $fatPer100g = null,
        ?array $clear = null,
    ): string {
        try {
            return match ($action) {
                'create' => $this->create($name, $category, $defaultUnit, $ciqualAlimCode, $kcalPer100g, $proteinPer100g, $carbsPer100g, $fatPer100g),
                'update' => $this->update($ingredientId, $name, $category, $defaultUnit, $ciqualAlimCode, $kcalPer100g, $proteinPer100g, $carbsPer100g, $fatPer100g, $clear),
                'delete' => $this->delete($ingredientId),
                default => json_encode(['error' => "Unknown action: {$action}. Use create, update, or delete (search_ingredients lists them)."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function create(
        ?string $name,
        ?string $category,
        ?string $defaultUnit,
        ?string $ciqualAlimCode,
        ?float $kcalPer100g,
        ?float $proteinPer100g,
        ?float $carbsPer100g,
        ?float $fatPer100g,
    ): string {
        if ($name === null || $category === null) {
            return json_encode(['error' => 'name and category are required for create.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $envelope = $this->bus->dispatch(new CreateIngredientCommand(
            userId: (string) $user->getId(),
            name: $name,
            category: $category,
            defaultUnit: $defaultUnit,
            ciqualAlimCode: $ciqualAlimCode,
            kcalPer100g: $kcalPer100g,
            proteinPer100g: $proteinPer100g,
            carbsPer100g: $carbsPer100g,
            fatPer100g: $fatPer100g,
        ));

        /** @var Ingredient $ingredient */
        $ingredient = $envelope->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'ingredient' => $this->serialize($ingredient)], JSON_THROW_ON_ERROR);
    }

    private function update(
        ?string $ingredientId,
        ?string $name,
        ?string $category,
        ?string $defaultUnit,
        ?string $ciqualAlimCode,
        ?float $kcalPer100g,
        ?float $proteinPer100g,
        ?float $carbsPer100g,
        ?float $fatPer100g,
        ?array $clear,
    ): string {
        if ($ingredientId === null) {
            return json_encode(['error' => 'ingredientId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new UpdateIngredientCommand(
            ingredientId: $ingredientId,
            name: $name,
            category: $category,
            defaultUnit: $defaultUnit,
            ciqualAlimCode: $ciqualAlimCode,
            kcalPer100g: $kcalPer100g,
            proteinPer100g: $proteinPer100g,
            carbsPer100g: $carbsPer100g,
            fatPer100g: $fatPer100g,
            clearFields: array_values(array_intersect($clear ?? [], self::CLEARABLE_FIELDS)),
        ));

        /** @var Ingredient $ingredient */
        $ingredient = $envelope->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'ingredient' => $this->serialize($ingredient)], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $ingredientId): string
    {
        if ($ingredientId === null) {
            return json_encode(['error' => 'ingredientId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteIngredientCommand(ingredientId: $ingredientId));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function serialize(Ingredient $ingredient): array
    {
        return [
            'id' => (string) $ingredient->getId(),
            'name' => $ingredient->getName(),
            'category' => $ingredient->getCategory()->value,
            'defaultUnit' => $ingredient->getDefaultUnit()?->value,
            'kcalPer100g' => $ingredient->getKcalPer100g(),
        ];
    }
}
