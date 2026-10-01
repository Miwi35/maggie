<?php

declare(strict_types=1);

namespace Maggie\Grocery\Controller;

use Maggie\Core\Entity\User;
use Maggie\Grocery\Message\ReorderGroceryItemsCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

final class ReorderGroceryItemsController
{
    public function __construct(
        private readonly MessageBusInterface $messageBus,
        private readonly Security $security,
    ) {
    }

    #[Route('/api/grocery/reorder', name: 'api_grocery_reorder', methods: ['PATCH'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);

        $items = $body['items'] ?? null;
        if (!\is_array($items) || [] === $items) {
            return new JsonResponse(['error' => 'Missing required field: items'], Response::HTTP_BAD_REQUEST);
        }

        foreach ($items as $entry) {
            if (!\is_array($entry)
                || !\is_string($entry['id'] ?? null)
                || '' === $entry['id']
                || !\is_int($entry['position'] ?? null)
            ) {
                return new JsonResponse(['error' => 'Each item must have a string "id" and an int "position"'], Response::HTTP_BAD_REQUEST);
            }
        }

        try {
            $this->messageBus->dispatch(new ReorderGroceryItemsCommand(
                userId: (string) $user->getId(),
                items: $items,
            ));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(['success' => true]);
    }
}
