<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Message\UpdateTransactionCommand;
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
 * The owner's hand on a transfer or rejection marking: read it, mark a line
 * or take the marking off, and list the lines a manual marking can pair it with.
 *
 * A dedicated endpoint rather than a `PATCH /api/transactions/{id}`: a
 * merge-patch cannot tell a field left out from a field set to null, so it
 * could unpair one leg and leave the other pointing at it. This one dispatches
 * the command `manage_transactions` dispatches, and what it writes is always
 * the owner's own decision (`manual`), which the detection never overwrites.
 */
final class TransactionTransferController
{
    /**
     * Wider than the detection's window on purpose: a transfer the detection
     * missed is usually one whose two legs are further apart than it allows.
     */
    public const int CANDIDATE_WINDOW_DAYS = 15;

    private const int MAX_CANDIDATES = 50;

    public function __construct(
        private readonly Security $security,
        private readonly TransactionRepository $transactionRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/finance/transactions/{id}/transfer', name: 'api_finance_transaction_transfer_show', methods: ['GET'])]
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

        return new JsonResponse($this->serializeTransfer($transaction));
    }

    #[Route('/api/finance/transactions/{id}/transfer-candidates', name: 'api_finance_transaction_transfer_candidates', methods: ['GET'])]
    public function candidates(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $transaction = $this->findOwned($user, $id);
        if (null === $transaction) {
            return new JsonResponse(['error' => 'Transaction not found'], Response::HTTP_NOT_FOUND);
        }

        // A transfer's other leg is on another account; a rejection is given
        // back on the account of the payment it cancels.
        $kind = TransferKind::tryFrom((string) $request->query->get('kind', TransferKind::Internal->value));
        if (null === $kind || TransferKind::None === $kind) {
            return new JsonResponse(['error' => 'kind must be one of: internal, rejected'], Response::HTTP_BAD_REQUEST);
        }

        $candidates = $this->transactionRepository->findTransferCandidates(
            $transaction,
            self::CANDIDATE_WINDOW_DAYS,
            includeJudged: true,
            sameAccount: TransferKind::Rejected === $kind,
        );

        return new JsonResponse([
            'candidates' => array_map(
                $this->summarize(...),
                \array_slice($candidates, 0, self::MAX_CANDIDATES),
            ),
        ]);
    }

    #[Route('/api/finance/transactions/{id}/transfer', name: 'api_finance_transaction_transfer_update', methods: ['PUT'])]
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

        $content = $request->getContent();

        try {
            $body = '' === $content ? [] : json_decode($content, true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            $body = null;
        }

        if (!\is_array($body)) {
            return new JsonResponse(['error' => 'The body must be a JSON object'], Response::HTTP_BAD_REQUEST);
        }

        $kind = $body['transferKind'] ?? null;
        if (!\is_string($kind) || null === TransferKind::tryFrom($kind)) {
            return new JsonResponse(
                ['error' => 'transferKind must be one of: '.implode(', ', array_column(TransferKind::cases(), 'value'))],
                Response::HTTP_BAD_REQUEST,
            );
        }

        $counterpartId = $body['counterpartId'] ?? null;
        if (null !== $counterpartId && (!\is_string($counterpartId) || !Ulid::isValid($counterpartId))) {
            return new JsonResponse(['error' => 'counterpartId must be a transaction id'], Response::HTTP_BAD_REQUEST);
        }
        if (null !== $counterpartId && TransferKind::None->value === $kind) {
            return new JsonResponse(
                ['error' => 'counterpartId only goes with a marking, not with a release'],
                Response::HTTP_BAD_REQUEST,
            );
        }

        try {
            $this->bus->dispatch(new UpdateTransactionCommand(
                userId: (string) $user->getId(),
                transactionId: $id,
                transferKind: $kind,
                transferSource: TransferSource::Manual->value,
                counterpartId: $counterpartId,
            ));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(['success' => true] + $this->serializeTransfer($transaction));
    }

    private function findOwned(User $user, string $id): ?Transaction
    {
        if (!Ulid::isValid($id)) {
            return null;
        }

        return $this->transactionRepository->findOneBy(['id' => $id, 'user' => $user]);
    }

    /** @return array<string, mixed> */
    private function serializeTransfer(Transaction $transaction): array
    {
        $counterpart = $transaction->getCounterpart();

        return [
            'transferKind' => $transaction->getTransferKind()->value,
            'transferSource' => $transaction->getTransferSource()->value,
            'counterpart' => null === $counterpart ? null : $this->summarize($counterpart),
        ];
    }

    /** @return array<string, mixed> */
    private function summarize(Transaction $transaction): array
    {
        return [
            'id' => (string) $transaction->getId(),
            'label' => $transaction->getLabel(),
            'amountCents' => $transaction->getAmountCents(),
            'currency' => $transaction->getCurrency(),
            'bookedAt' => $transaction->getBookedAt()->format('Y-m-d'),
            'accountId' => (string) $transaction->getAccount()->getId(),
            'accountName' => $transaction->getAccount()->getName(),
        ];
    }
}
