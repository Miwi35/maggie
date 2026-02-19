<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Message\CreateIngredientCommand;
use Maggie\Core\Repository\UserRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'create_ingredient', description: 'Create a new food ingredient. Categories: produce, dairy, meat, fish, grain, spice, condiment, frozen, beverage, other. Units: g, kg, ml, l, cl, piece, bunch, can, bottle, pack, sachet.')]
class CreateIngredientTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(
        string $name,
        string $category,
        ?string $defaultUnit = null,
    ): string {
        try {
            $users = $this->userRepository->findAll();
            $user = $users[0] ?? throw new \DomainException('No user found.');

            $envelope = $this->bus->dispatch(new CreateIngredientCommand(
                userId: (string) $user->getId(),
                name: $name,
                category: $category,
                defaultUnit: $defaultUnit,
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
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
