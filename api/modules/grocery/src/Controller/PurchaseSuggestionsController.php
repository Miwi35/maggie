<?php

declare(strict_types=1);

namespace Maggie\Grocery\Controller;

use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Message\SnoozePurchaseSuggestionCommand;
use Maggie\Grocery\Service\PurchaseSuggestions;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;

/**
 * « Tu en prends souvent » (MAG-495): the products the past errands say are
 * due, and « Pas besoin » to put one off. Adding one is an ordinary
 * `POST /api/grocery/add-item` — a suggestion taken is a manual addition.
 */
final class PurchaseSuggestionsController
{
    public function __construct(
        private readonly Security $security,
        private readonly PurchaseSuggestions $suggestions,
        private readonly MessageBusInterface $messageBus,
    ) {
    }

    #[Route('/api/grocery-suggestions', name: 'api_grocery_suggestions', methods: ['GET'])]
    public function list(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse($this->suggestions->suggest($user));
    }

    #[Route('/api/grocery-suggestions/{productId}/snooze', name: 'api_grocery_suggestions_snooze', methods: ['POST'])]
    public function snooze(string $productId): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        try {
            $envelope = $this->messageBus->dispatch(new SnoozePurchaseSuggestionCommand(
                userId: (string) $user->getId(),
                productId: $productId,
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

        /** @var Product $product */
        $product = $envelope->last(HandledStamp::class)?->getResult();

        return new JsonResponse([
            'success' => true,
            'productId' => (string) $product->getId(),
            'suggestionSnoozedUntil' => $product->getSuggestionSnoozedUntil()?->format('Y-m-d'),
        ]);
    }
}
