<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Message\AllocateTransactionCommand;
use Maggie\Finance\Message\RemoveAllocationCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\GetTransactionAllocation;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/**
 * « Ce virement est… » on the sheet of a transfer with a person, in « À trier »
 * and in the review (MAG-453): what it is and the shares of créances or dettes
 * it gives back. Its own endpoint, like `my-share` and `debt`: the answer and
 * its allocations are one decision, written together or not at all.
 */
final class TransactionAllocationController
{
    public function __construct(
        private readonly Security $security,
        private readonly TransactionRepository $transactionRepository,
        private readonly GetTransactionAllocation $getAllocation,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/finance/transactions/{id}/allocation', name: 'api_finance_transaction_allocation_show', methods: ['GET'])]
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

        return new JsonResponse($this->getAllocation->execute($transaction));
    }

    #[Route('/api/finance/transactions/{id}/allocation', name: 'api_finance_transaction_allocation_put', methods: ['PUT'])]
    public function put(string $id, Request $request): JsonResponse
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

        try {
            $command = self::command((string) $user->getId(), $id, $body);
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        try {
            $this->bus->dispatch($command);
        } catch (HandlerFailedException $e) {
            return new JsonResponse(['error' => ($e->getPrevious() ?? $e)->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->getAllocation->execute($transaction));
    }

    #[Route('/api/finance/transactions/{id}/allocation', name: 'api_finance_transaction_allocation_delete', methods: ['DELETE'])]
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
            $this->bus->dispatch(new RemoveAllocationCommand(userId: (string) $user->getId(), transactionId: $id));
        } catch (HandlerFailedException $e) {
            return new JsonResponse(['error' => ($e->getPrevious() ?? $e)->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->getAllocation->execute($transaction));
    }

    /**
     * @param array<mixed> $body
     *
     * @throws \InvalidArgumentException when a field has the wrong shape
     */
    private static function command(string $userId, string $transactionId, array $body): AllocateTransactionCommand
    {
        $kind = $body['kind'] ?? null;
        if (!\is_string($kind)) {
            throw new \InvalidArgumentException('kind is required: income, repayment or new_debt for a received transfer, repayment, new_claim or other for a sent one');
        }

        $allocations = [];
        $given = $body['allocations'] ?? [];
        if (!\is_array($given) || !array_is_list($given)) {
            throw new \InvalidArgumentException('allocations must be a list of {shareId, amountCents}');
        }
        foreach ($given as $allocation) {
            $shareId = \is_array($allocation) ? ($allocation['shareId'] ?? null) : null;
            $amountCents = \is_array($allocation) ? ($allocation['amountCents'] ?? null) : null;
            if (!\is_string($shareId) || !\is_int($amountCents)) {
                throw new \InvalidArgumentException('allocations must be a list of {shareId, amountCents}');
            }
            $allocations[] = ['shareId' => $shareId, 'amountCents' => $amountCents];
        }

        $debt = null;
        if (null !== ($body['debt'] ?? null)) {
            $fields = self::object($body['debt'], 'debt');
            if (!\is_int($body['debt']['amountCents'] ?? null)) {
                throw new \InvalidArgumentException('debt.amountCents must be an integer number of cents');
            }
            $debt = [...$fields, 'amountCents' => $body['debt']['amountCents']];
        }
        $claim = null === ($body['claim'] ?? null) ? null : self::object($body['claim'], 'claim');

        return new AllocateTransactionCommand(
            userId: $userId,
            transactionId: $transactionId,
            kind: $kind,
            allocations: $allocations,
            debt: $debt,
            claim: $claim,
        );
    }

    /**
     * The fields a new dette and a new créance share.
     *
     * @return array{contactId: ?string, object: ?string, notes: ?string}
     *
     * @throws \InvalidArgumentException
     */
    private static function object(mixed $value, string $name): array
    {
        if (!\is_array($value)) {
            throw new \InvalidArgumentException("{$name} must be an object");
        }
        $fields = [];
        foreach (['contactId', 'object', 'notes'] as $field) {
            $fields[$field] = $value[$field] ?? null;
            if (null !== $fields[$field] && !\is_string($fields[$field])) {
                throw new \InvalidArgumentException("{$name}.{$field} must be a string");
            }
        }

        return $fields;
    }

    private function findOwned(User $user, string $id): ?Transaction
    {
        if (!Ulid::isValid($id)) {
            return null;
        }

        return $this->transactionRepository->findOneBy(['id' => $id, 'user' => $user]);
    }
}
