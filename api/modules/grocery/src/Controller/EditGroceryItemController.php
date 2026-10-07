<?php

declare(strict_types=1);

namespace Maggie\Grocery\Controller;

use Maggie\Core\Entity\User;
use Maggie\Grocery\Message\EditGroceryItemCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

final class EditGroceryItemController
{
    public function __construct(
        private readonly MessageBusInterface $messageBus,
        private readonly Security $security,
    ) {
    }

    #[Route('/api/grocery/edit-item/{id}', name: 'api_grocery_edit_item', methods: ['PATCH'])]
    public function __invoke(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);

        if (\array_key_exists('label', $body) && (!\is_string($body['label']) || '' === $body['label'])) {
            return new JsonResponse(['error' => 'Label must be a non-empty string'], Response::HTTP_BAD_REQUEST);
        }

        if (isset($body['quantity']) && (!is_numeric($body['quantity']) || (float) $body['quantity'] <= 0)) {
            return new JsonResponse(['error' => 'Quantity must be a positive number'], Response::HTTP_BAD_REQUEST);
        }

        $this->messageBus->dispatch(new EditGroceryItemCommand(
            groceryItemId: $id,
            userId: (string) $user->getId(),
            label: $body['label'] ?? null,
            quantity: isset($body['quantity']) ? (float) $body['quantity'] : null,
            unit: $body['unit'] ?? null,
            storeId: $body['storeId'] ?? null,
            storeName: $body['storeName'] ?? null,
            category: $body['category'] ?? null,
        ));

        return new JsonResponse(['success' => true]);
    }
}
