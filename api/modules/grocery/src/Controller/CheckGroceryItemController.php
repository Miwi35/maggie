<?php

declare(strict_types=1);

namespace Maggie\Grocery\Controller;

use Maggie\Core\Entity\User;
use Maggie\Grocery\Message\CheckGroceryItemCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

final class CheckGroceryItemController
{
    public function __construct(
        private readonly MessageBusInterface $messageBus,
        private readonly Security $security,
    ) {
    }

    #[Route('/api/grocery_items/{id}', name: 'api_grocery_item_check', methods: ['PATCH'])]
    public function __invoke(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);

        if (!isset($body['checked']) || !\is_bool($body['checked'])) {
            return new JsonResponse(['error' => 'Missing required field: checked'], Response::HTTP_BAD_REQUEST);
        }

        try {
            $this->messageBus->dispatch(new CheckGroceryItemCommand(
                groceryItemId: $id,
                userId: (string) $user->getId(),
                checked: $body['checked'],
            ));

            return new JsonResponse([
                'success' => true,
                'checked' => $body['checked'],
            ]);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof \DomainException) {
                return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_NOT_FOUND);
            }
            throw $e;
        } catch (\DomainException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_NOT_FOUND);
        }
    }
}
