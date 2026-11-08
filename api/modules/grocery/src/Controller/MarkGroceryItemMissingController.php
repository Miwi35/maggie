<?php

declare(strict_types=1);

namespace Maggie\Grocery\Controller;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Message\MarkGroceryItemMissingCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

/** `POST` marks the article missing in its store, `DELETE` cancels it (MAG-764). */
final class MarkGroceryItemMissingController
{
    public function __construct(
        private readonly MessageBusInterface $messageBus,
        private readonly Security $security,
        private readonly EntityManagerInterface $em,
    ) {
    }

    #[Route('/api/grocery_items/{id}/missing', name: 'api_grocery_item_mark_missing', methods: ['POST'])]
    #[Route('/api/grocery_items/{id}/missing', name: 'api_grocery_item_clear_missing', methods: ['DELETE'])]
    public function __invoke(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $missing = Request::METHOD_POST === $request->getMethod();

        try {
            $this->messageBus->dispatch(new MarkGroceryItemMissingCommand(
                groceryItemId: $id,
                userId: (string) $user->getId(),
                missing: $missing,
            ));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof \DomainException) {
                return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
            }
            throw $e;
        } catch (\DomainException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        // The handler has just loaded it: the identity map answers, no query.
        $item = $this->em->find(GroceryItem::class, $id);

        return new JsonResponse([
            'success' => true,
            'missing' => null !== $item?->getMissingAt(),
            'store' => null !== $item?->getStore() ? (string) $item->getStore()->getId() : null,
            'missingFromStore' => null !== $item?->getMissingFromStore() ? (string) $item->getMissingFromStore()->getId() : null,
        ]);
    }
}
