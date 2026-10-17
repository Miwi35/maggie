<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Message\RecordDebtCommand;
use Maggie\Finance\Message\RemoveDebtCommand;
use Maggie\Finance\Repository\PeerDebtRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/**
 * « À rembourser » on the sheet of a received transfer (MAG-452): the amount
 * to give back to the person who sent it, which becomes a dette and leaves the
 * incomes. Its own endpoint, like `my-share`: the choice and the dette are one
 * decision, written together or not at all.
 */
final class TransactionDebtController
{
    public function __construct(
        private readonly Security $security,
        private readonly TransactionRepository $transactionRepository,
        private readonly PeerDebtRepository $peerDebtRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/finance/transactions/{id}/debt', name: 'api_finance_transaction_debt_show', methods: ['GET'])]
    public function show(string $id): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $transaction = $this->findOwned($user, $id);
        if (null === $transaction) {
            return new JsonResponse(['error' => 'Transaction not found'], Response::HTTP_NOT_FOUND);
        }

        return new JsonResponse($this->serialize($transaction));
    }

    #[Route('/api/finance/transactions/{id}/debt', name: 'api_finance_transaction_debt_record', methods: ['POST'])]
    public function record(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $transaction = $this->findOwned($user, $id);
        if (null === $transaction) {
            return new JsonResponse(['error' => 'Transaction not found'], Response::HTTP_NOT_FOUND);
        }

        try {
            $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            $body = null;
        }
        if (!\is_array($body)) {
            return new JsonResponse(['error' => 'The body must be a JSON object'], Response::HTTP_BAD_REQUEST);
        }

        $contactId = $body['contactId'] ?? null;
        if (!\is_string($contactId)) {
            return new JsonResponse(['error' => 'contactId must be the id of a contact'], Response::HTTP_BAD_REQUEST);
        }
        $amountCents = $body['amountCents'] ?? null;
        if (!\is_int($amountCents)) {
            return new JsonResponse(['error' => 'amountCents must be an integer number of cents'], Response::HTTP_BAD_REQUEST);
        }
        foreach (['object', 'notes'] as $field) {
            if (null !== ($body[$field] ?? null) && !\is_string($body[$field])) {
                return new JsonResponse(['error' => "{$field} must be a string"], Response::HTTP_BAD_REQUEST);
            }
        }

        $existed = null !== $this->peerDebtRepository->findOneByOriginTransaction($transaction);

        try {
            $this->bus->dispatch(new RecordDebtCommand(
                userId: (string) $user->getId(),
                transactionId: $id,
                contactId: $contactId,
                amountCents: $amountCents,
                object: $body['object'] ?? null,
                notes: $body['notes'] ?? null,
            ));
        } catch (HandlerFailedException $e) {
            return new JsonResponse(['error' => ($e->getPrevious() ?? $e)->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->serialize($transaction), $existed ? Response::HTTP_OK : Response::HTTP_CREATED);
    }

    #[Route('/api/finance/transactions/{id}/debt', name: 'api_finance_transaction_debt_delete', methods: ['DELETE'])]
    public function delete(string $id): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $transaction = $this->findOwned($user, $id);
        if (null === $transaction) {
            return new JsonResponse(['error' => 'Transaction not found'], Response::HTTP_NOT_FOUND);
        }

        try {
            $this->bus->dispatch(new RemoveDebtCommand(userId: (string) $user->getId(), transactionId: $id));
        } catch (HandlerFailedException $e) {
            return new JsonResponse(['error' => ($e->getPrevious() ?? $e)->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->serialize($transaction));
    }

    private function findOwned(User $user, string $id): ?Transaction
    {
        if (!Ulid::isValid($id)) {
            return null;
        }

        return $this->transactionRepository->findOneBy(['id' => $id, 'user' => $user]);
    }

    /** @return array<string, mixed> */
    private function serialize(Transaction $transaction): array
    {
        $debt = $this->peerDebtRepository->findOneByOriginTransaction($transaction);
        $debt = null !== $debt && PeerDebtDirection::IOwe === $debt->getDirection() ? $debt : null;

        return [
            'transactionId' => (string) $transaction->getId(),
            'amountCents' => $transaction->getAmountCents(),
            'canBeDebt' => $transaction->canBeDebt(),
            'suggestedContactId' => null !== $transaction->getContact() ? (string) $transaction->getContact()->getId() : null,
            'peerDebt' => $debt instanceof PeerDebt ? $debt->toArray() : null,
        ];
    }
}
