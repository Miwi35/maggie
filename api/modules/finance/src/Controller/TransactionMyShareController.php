<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Message\RemoveMyShareCommand;
use Maggie\Finance\Message\SetMyShareCommand;
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
 * « Partage » on the transaction sheet (MAG-451): the part of a spend the
 * owner kept and the créance the rest becomes, split between people.
 *
 * Its own endpoint rather than a field of `PATCH /api/transactions/{id}`: the
 * part and the créance are one decision, written together or not at all.
 */
final class TransactionMyShareController
{
    public function __construct(
        private readonly Security $security,
        private readonly TransactionRepository $transactionRepository,
        private readonly PeerDebtRepository $peerDebtRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/finance/transactions/{id}/my-share', name: 'api_finance_transaction_my_share_show', methods: ['GET'])]
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

        return new JsonResponse($this->serialize($transaction, $this->peerDebtRepository->findOneByOriginTransaction($transaction)));
    }

    #[Route('/api/finance/transactions/{id}/my-share', name: 'api_finance_transaction_my_share_update', methods: ['PUT'])]
    public function update(string $id, Request $request): JsonResponse
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

        $myShareCents = $body['myShareCents'] ?? null;
        if (!\is_int($myShareCents)) {
            return new JsonResponse(['error' => 'myShareCents must be an integer number of cents'], Response::HTTP_BAD_REQUEST);
        }
        foreach (['object', 'notes'] as $field) {
            if (null !== ($body[$field] ?? null) && !\is_string($body[$field])) {
                return new JsonResponse(['error' => "{$field} must be a string"], Response::HTTP_BAD_REQUEST);
            }
        }
        $shares = self::sharesOf($body['shares'] ?? null);
        if (null === $shares) {
            return new JsonResponse(['error' => 'shares must be a list of {contactId, amountCents}'], Response::HTTP_BAD_REQUEST);
        }

        try {
            $this->bus->dispatch(new SetMyShareCommand(
                userId: (string) $user->getId(),
                transactionId: $id,
                myShareCents: $myShareCents,
                shares: $shares,
                object: $body['object'] ?? null,
                notes: $body['notes'] ?? null,
            ));
        } catch (HandlerFailedException $e) {
            return new JsonResponse(['error' => ($e->getPrevious() ?? $e)->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->serialize($transaction, $this->peerDebtRepository->findOneByOriginTransaction($transaction)));
    }

    #[Route('/api/finance/transactions/{id}/my-share', name: 'api_finance_transaction_my_share_delete', methods: ['DELETE'])]
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
            $this->bus->dispatch(new RemoveMyShareCommand(userId: (string) $user->getId(), transactionId: $id));
        } catch (HandlerFailedException $e) {
            return new JsonResponse(['error' => ($e->getPrevious() ?? $e)->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->serialize($transaction, null));
    }

    /** @return list<array{contactId: string, amountCents: int|null}>|null null when malformed */
    private static function sharesOf(mixed $shares): ?array
    {
        if (!\is_array($shares) || !array_is_list($shares)) {
            return null;
        }

        $parsed = [];
        foreach ($shares as $share) {
            $contactId = \is_array($share) ? ($share['contactId'] ?? null) : null;
            $amountCents = \is_array($share) ? ($share['amountCents'] ?? null) : null;
            if (!\is_string($contactId) || (null !== $amountCents && !\is_int($amountCents))) {
                return null;
            }
            $parsed[] = ['contactId' => $contactId, 'amountCents' => $amountCents];
        }

        return $parsed;
    }

    private function findOwned(User $user, string $id): ?Transaction
    {
        if (!Ulid::isValid($id)) {
            return null;
        }

        return $this->transactionRepository->findOneBy(['id' => $id, 'user' => $user]);
    }

    /** @return array<string, mixed> */
    private function serialize(Transaction $transaction, ?PeerDebt $debt): array
    {
        return [
            'transactionId' => (string) $transaction->getId(),
            'amountCents' => $transaction->getAmountCents(),
            'myShareCents' => $transaction->getMyShareCents(),
            'canBeShared' => $transaction->canBeShared(),
            'peerDebt' => $debt?->toArray(),
        ];
    }
}
