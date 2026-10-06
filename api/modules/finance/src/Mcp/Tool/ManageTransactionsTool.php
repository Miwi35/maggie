<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Message\CreateTransactionCommand;
use Maggie\Finance\Message\DeleteTransactionCommand;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_transactions', description: 'List, create, update, delete, or categorize transactions. Amounts are signed integer cents: negative = expense/debit, positive = income/credit. bookedAt is an ISO date (defaults to today). status is one of spent, committed, planned, to_arbitrate. On update, only provided fields change; to remove the category, list categoryId in clear.')]
class ManageTransactionsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly TransactionRepository $transactionRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param list<string>|null $clear */
    public function __invoke(
        string $action,
        ?string $transactionId = null,
        ?string $accountId = null,
        ?int $amountCents = null,
        ?string $label = null,
        ?string $bookedAt = null,
        ?string $status = null,
        ?string $currency = null,
        ?bool $isExceptional = null,
        ?string $categoryId = null,
        ?array $clear = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($accountId, $amountCents, $label, $bookedAt, $status, $currency, $isExceptional, $categoryId),
                'update' => $this->update($transactionId, $accountId, $amountCents, $label, $bookedAt, $status, $currency, $isExceptional, $categoryId, $clear),
                'categorize' => $this->categorize($transactionId, $categoryId),
                'delete' => $this->delete($transactionId),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, create, update, categorize, or delete."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function list(): string
    {
        $user = $this->userContext->requireUser();

        $transactions = $this->transactionRepository->findByUser($user);

        return json_encode([
            'transactions' => array_map(fn (Transaction $t) => $this->serialize($t), $transactions),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $accountId, ?int $amountCents, ?string $label, ?string $bookedAt, ?string $status, ?string $currency, ?bool $isExceptional, ?string $categoryId): string
    {
        if (null === $accountId || null === $label || null === $amountCents) {
            return json_encode(['error' => 'accountId, amountCents and label are required for create.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $envelope = $this->bus->dispatch(new CreateTransactionCommand(
            userId: (string) $user->getId(),
            accountId: $accountId,
            amountCents: $amountCents,
            label: $label,
            bookedAt: $bookedAt ?? (new \DateTimeImmutable())->format('Y-m-d'),
            status: $status ?? 'spent',
            currency: $currency ?? 'EUR',
            isExceptional: $isExceptional ?? false,
            categoryId: $categoryId,
        ));

        /** @var Transaction $transaction */
        $transaction = $envelope->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'transaction' => $this->serialize($transaction),
        ], JSON_THROW_ON_ERROR);
    }

    /** @param list<string>|null $clear */
    private function update(?string $transactionId, ?string $accountId, ?int $amountCents, ?string $label, ?string $bookedAt, ?string $status, ?string $currency, ?bool $isExceptional, ?string $categoryId, ?array $clear): string
    {
        if (null === $transactionId) {
            return json_encode(['error' => 'transactionId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new UpdateTransactionCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            transactionId: $transactionId,
            accountId: $accountId,
            amountCents: $amountCents,
            label: $label,
            bookedAt: $bookedAt,
            status: $status,
            currency: $currency,
            isExceptional: $isExceptional,
            categoryId: $categoryId,
            clearFields: array_values(array_intersect($clear ?? [], ['categoryId'])),
        ));

        /** @var Transaction $transaction */
        $transaction = $envelope->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'transaction' => $this->serialize($transaction),
        ], JSON_THROW_ON_ERROR);
    }

    private function categorize(?string $transactionId, ?string $categoryId): string
    {
        if (null === $transactionId || null === $categoryId) {
            return json_encode(['error' => 'transactionId and categoryId are required for categorize.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new UpdateTransactionCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            transactionId: $transactionId,
            categoryId: $categoryId,
        ));

        /** @var Transaction $transaction */
        $transaction = $envelope->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'transaction' => $this->serialize($transaction),
        ], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $transactionId): string
    {
        if (null === $transactionId) {
            return json_encode(['error' => 'transactionId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteTransactionCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            transactionId: $transactionId,
        ));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function serialize(Transaction $transaction): array
    {
        return [
            'id' => (string) $transaction->getId(),
            'label' => $transaction->getLabel(),
            'amountCents' => $transaction->getAmountCents(),
            'currency' => $transaction->getCurrency(),
            'bookedAt' => $transaction->getBookedAt()->format('Y-m-d'),
            'status' => $transaction->getStatus()->value,
            'isExceptional' => $transaction->isExceptional(),
            'accountId' => (string) $transaction->getAccount()->getId(),
            'categoryId' => null !== $transaction->getCategory() ? (string) $transaction->getCategory()->getId() : null,
        ];
    }
}
