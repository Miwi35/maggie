<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Message\SettleRejectionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/**
 * « Régularisé » on a rejected payment, and « Annuler » (MAG-424). Either leg
 * of the rejection names it. A dedicated endpoint rather than a PATCH of the
 * transaction: the date is set on both legs at once.
 */
final class RejectionSettlementController
{
    public function __construct(
        private readonly Security $security,
        private readonly TransactionRepository $transactionRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/finance/transactions/{id}/rejection-settlement', name: 'api_finance_transaction_settle_rejection', methods: ['POST'])]
    public function settle(string $id): JsonResponse
    {
        return $this->answer($id, true);
    }

    #[Route('/api/finance/transactions/{id}/rejection-settlement', name: 'api_finance_transaction_unsettle_rejection', methods: ['DELETE'])]
    public function unsettle(string $id): JsonResponse
    {
        return $this->answer($id, false);
    }

    private function answer(string $id, bool $settled): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $transaction = Ulid::isValid($id)
            ? $this->transactionRepository->findOneBy(['id' => $id, 'user' => $user])
            : null;
        if (null === $transaction) {
            return new JsonResponse(['error' => 'Transaction not found'], Response::HTTP_NOT_FOUND);
        }

        try {
            $envelope = $this->bus->dispatch(new SettleRejectionCommand((string) $transaction->getId(), $settled));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        /** @var Transaction $transaction */
        $transaction = $envelope->last(HandledStamp::class)?->getResult();

        return new JsonResponse([
            'success' => true,
            'transactionId' => (string) $transaction->getId(),
            'counterpartId' => null === $transaction->getCounterpart() ? null : (string) $transaction->getCounterpart()->getId(),
            'rejectionSettledAt' => $transaction->getRejectionSettledAt()?->format(\DateTimeInterface::ATOM),
        ]);
    }
}
