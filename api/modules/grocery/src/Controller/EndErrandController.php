<?php

declare(strict_types=1);

namespace Maggie\Grocery\Controller;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Message\EndErrandCommand;
use Maggie\Grocery\Message\EndErrandResult;
use Maggie\Grocery\Repository\GroceryListRepository;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;

final class EndErrandController
{
    public function __construct(
        private readonly MessageBusInterface $messageBus,
        private readonly Security $security,
        private readonly GroceryListRepository $groceryListRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    #[Route('/api/grocery/end-errand', name: 'api_grocery_end_errand', methods: ['POST'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $content = $request->getContent();
        $body = '' === $content ? [] : json_decode($content, true, 512, \JSON_THROW_ON_ERROR);

        $storeId = $body['storeId'] ?? null;
        if (null !== $storeId && !\is_string($storeId)) {
            return new JsonResponse(['error' => 'storeId must be a string'], Response::HTTP_BAD_REQUEST);
        }

        try {
            $envelope = $this->messageBus->dispatch(new EndErrandCommand(
                userId: (string) $user->getId(),
                storeId: $storeId,
            ));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        /** @var EndErrandResult $result */
        $result = $envelope->last(HandledStamp::class)->getResult();

        // Re-fetch the list and its items via direct queries to avoid stale state
        // from the messenger handler's unit of work.
        $list = $this->groceryListRepository->findOrCreateForUser($user);
        $items = $this->em->getRepository(GroceryItem::class)->findBy(['groceryList' => $list]);

        $remaining = [];
        foreach ($items as $item) {
            if ($item->isChecked()) {
                continue;
            }
            if (null !== $storeId) {
                $itemStoreId = $item->getStore()?->getId();
                if (null === $itemStoreId || (string) $itemStoreId !== $storeId) {
                    continue;
                }
            }
            $remaining[] = [
                'id' => (string) $item->getId(),
                'label' => $item->getLabel(),
                'quantity' => $item->getQuantity(),
                'unit' => $item->getUnit()?->value,
                'store' => null === $item->getStore() ? null : [
                    'id' => (string) $item->getStore()->getId(),
                    'name' => $item->getStore()->getName(),
                ],
            ];
        }

        return new JsonResponse([
            'success' => true,
            'remainingItems' => $remaining,
            'remainingCount' => \count($remaining),
            'restockedProducts' => array_map(static fn ($product) => [
                'id' => (string) $product->getId(),
                'name' => $product->getName(),
                'stockState' => $product->getStockState()->value,
            ], $result->restockedProducts),
            'restockedCount' => \count($result->restockedProducts),
        ]);
    }
}
