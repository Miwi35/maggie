<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Message\CreateIngredientCommand;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'create_ingredient', description: 'Create a new food ingredient. Categories: produce, dairy, meat, fish, grain, spice, condiment, frozen, beverage, other. Units: g, kg, ml, l, cl, piece, bunch, can, bottle, pack, sachet. Use search_ciqual_foods first to find nutrition data and pass the ciqualAlimCode.')]
class CreateIngredientTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $name,
        string $category,
        ?string $defaultUnit = null,
        ?string $ciqualAlimCode = null,
        ?float $kcalPer100g = null,
        ?float $proteinPer100g = null,
        ?float $carbsPer100g = null,
        ?float $fatPer100g = null,
    ): string {
        try {
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

            return json_encode([
                'success' => true,
                'ingredient' => [
                    'id' => (string) $ingredient->getId(),
                    'name' => $ingredient->getName(),
                    'category' => $ingredient->getCategory()->value,
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
