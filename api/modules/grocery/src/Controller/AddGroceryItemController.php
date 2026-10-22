<?php

declare(strict_types=1);

namespace Maggie\Grocery\Controller;

use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Exception\InvalidGroceryQuantityException;
use Maggie\Grocery\Message\AddGroceryItemCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;

final class AddGroceryItemController
{
    public function __construct(
        private readonly MessageBusInterface $messageBus,
        private readonly Security $security,
    ) {
    }

    #[Route('/api/grocery/add-item', name: 'api_grocery_add_item', methods: ['POST'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);

        $label = $body['label'] ?? null;
        if (!\is_string($label) || '' === $label) {
            return new JsonResponse(['error' => 'Missing required field: label'], Response::HTTP_BAD_REQUEST);
        }

        $packagings = $body['packagings'] ?? null;
        if (null !== $packagings && (!\is_int($packagings) || $packagings < 1)) {
            return new JsonResponse(['error' => 'packagings must be a whole number of at least 1'], Response::HTTP_BAD_REQUEST);
        }

        try {
            $envelope = $this->messageBus->dispatch(new AddGroceryItemCommand(
                userId: (string) $user->getId(),
                label: $label,
                quantity: isset($body['quantity']) ? (float) $body['quantity'] : null,
                unit: $body['unit'] ?? null,
                storeId: $body['storeId'] ?? null,
                storeName: $body['storeName'] ?? null,
                category: $body['category'] ?? null,
                packagings: $packagings,
            ));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof InvalidGroceryQuantityException) {
                return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
            }

            throw $e;
        }

        $list = $envelope->last(HandledStamp::class)?->getResult();
        $itemCount = null !== $list ? \count($list->getItems()->filter(fn (GroceryItem $i) => !$i->isChecked())) : 0;

        return new JsonResponse([
            'success' => true,
            'itemCount' => $itemCount,
        ]);
    }
}
