<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\UpdateRecipeCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Recipe, Recipe> */
class UpdateRecipeProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Recipe
    {
        $ingredients = $this->extractIngredients($context);

        $envelope = $this->bus->dispatch(new UpdateRecipeCommand(
            recipeId: (string) $data->getId(),
            name: $data->getName(),
            servings: $data->getServings(),
            tags: $data->getTags(),
            notes: $data->getNotes(),
            ingredients: $ingredients,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }

    /** @return array<array{quantity: float, unit: string, ingredientId?: string, ciqualAlimCode?: string}>|null */
    private function extractIngredients(array $context): ?array
    {
        $request = $context['request'] ?? null;
        if ($request === null) {
            return null;
        }

        $body = json_decode($request->getContent(), true);
        if (!isset($body['ingredients']) || !\is_array($body['ingredients'])) {
            return null;
        }

        return array_map(fn (array $item) => [
            'quantity' => (float) ($item['quantity'] ?? 0),
            'unit' => $item['unit'] ?? 'g',
            ...($this->extractId($item, 'ingredient') !== null ? ['ingredientId' => $this->extractId($item, 'ingredient')] : []),
            ...(isset($item['ciqualAlimCode']) && \is_string($item['ciqualAlimCode']) ? ['ciqualAlimCode' => $item['ciqualAlimCode']] : []),
        ], $body['ingredients']);
    }

    private function extractId(array $item, string $key): ?string
    {
        if (!isset($item[$key]) || !\is_string($item[$key])) {
            return null;
        }

        $iri = $item[$key];

        // Extract ULID from IRI (e.g. "/api/ingredients/01HXYZ..." → "01HXYZ...")
        $parts = explode('/', rtrim($iri, '/'));

        return end($parts);
    }
}
