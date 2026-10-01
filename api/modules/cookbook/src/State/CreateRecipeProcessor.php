<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\CreateRecipeCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Recipe, Recipe> */
class CreateRecipeProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Recipe
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $ingredients = $this->extractIngredients($context);

        $envelope = $this->bus->dispatch(new CreateRecipeCommand(
            userId: (string) $user->getId(),
            name: $data->getName(),
            servings: $data->getServings(),
            tags: $data->getTags(),
            notes: $data->getNotes(),
            ingredients: $ingredients,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }

    /**
     * @param array<string, mixed> $context
     *
     * @return array<array{quantity: float, unit: string, ingredientId?: string, ciqualAlimCode?: string}>|null
     */
    private function extractIngredients(array $context): ?array
    {
        $request = $context['request'] ?? null;
        if (null === $request) {
            return null;
        }

        $body = json_decode($request->getContent(), true);
        if (!isset($body['ingredients']) || !\is_array($body['ingredients'])) {
            return null;
        }

        return array_map(fn (array $item) => [
            'quantity' => (float) ($item['quantity'] ?? 0),
            'unit' => $item['unit'] ?? 'g',
            ...(null !== $this->extractId($item, 'ingredient') ? ['ingredientId' => $this->extractId($item, 'ingredient')] : []),
            ...(isset($item['ciqualAlimCode']) && \is_string($item['ciqualAlimCode']) ? ['ciqualAlimCode' => $item['ciqualAlimCode']] : []),
        ], $body['ingredients']);
    }

    /** @param array<string, mixed> $item */
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
