<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Enum\PeerDebtStatus;
use Maggie\Finance\Enum\PeerKind;
use Maggie\Finance\Message\AllocateTransactionCommand;
use Maggie\Finance\Message\RecordDebtCommand;
use Maggie\Finance\Message\RemoveAllocationCommand;
use Maggie\Finance\Message\RemoveDebtCommand;
use Maggie\Finance\Message\RemoveMyShareCommand;
use Maggie\Finance\Message\SetMyShareCommand;
use Maggie\Finance\Repository\ContactRepository;
use Maggie\Finance\Repository\PeerDebtAllocationRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\GetPeerDebtSummary;
use Maggie\Finance\UseCase\GetTransactionAllocation;
use Maggie\Finance\UseCase\ListPeerDebts;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'manage_peer_debts', description: 'Créances (people owe the owner) and dettes (the owner owes them); bank loans: manage_loans. Integer cents. list: optional contactId, status, direction. summary: owedToMeCents, iOweCents per contact. set_my_share: transactionId of a spend, myShareCents (owner\'s part, 0 <= part < amount), shares [{contactId, amountCents}] for the others (omit amountCents to split equally, else sum = amount - part), optional object, notes; null myShareCents removes it. record_debt: transactionId of a received transfer, contactId (sender), amountCents to give back (0 < amount <= transfer), optional object, notes; null amountCents removes it; both refused once repaid. allocate: transactionId, kind; received: income, repayment, new_debt (contactId, amountCents); sent: repayment, new_claim (contactId), other. repayment needs shareId (from list) and amountCents <= its remainder, 0 detaches; one allocation per call; kind null undoes; internal transfers refused. Returns peerKind, allocations, unallocatedCents.')]
class ManagePeerDebtsTool
{
    private const ACTIONS = ['list', 'summary', 'set_my_share', 'record_debt', 'allocate'];

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly ListPeerDebts $listPeerDebts,
        private readonly ContactRepository $contactRepository,
        private readonly GetPeerDebtSummary $summary,
        private readonly McpUserContext $userContext,
        private readonly TransactionRepository $transactionRepository,
        private readonly PeerDebtAllocationRepository $allocationRepository,
        private readonly GetTransactionAllocation $getAllocation,
    ) {
    }

    /** @param list<array<string, mixed>>|null $shares what the agent sent: checked one by one */
    public function __invoke(
        #[Schema(enum: self::ACTIONS)]
        string $action,
        ?string $contactId = null,
        #[Schema(enum: ['open', 'settled'])]
        ?string $status = null,
        #[Schema(enum: ['owed_to_me', 'i_owe'])]
        ?string $direction = null,
        ?string $transactionId = null,
        ?int $myShareCents = null,
        #[Schema(items: ['type' => 'object', 'properties' => ['contactId' => ['type' => 'string'], 'amountCents' => ['type' => 'integer']], 'required' => ['contactId']])]
        ?array $shares = null,
        ?string $object = null,
        ?string $notes = null,
        ?int $amountCents = null,
        #[Schema(enum: ['income', 'repayment', 'new_debt', 'new_claim', 'other'])]
        ?string $kind = null,
        ?string $shareId = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list($contactId, $status, $direction),
                'summary' => self::json($this->summary->execute($this->userContext->requireUser())),
                'set_my_share' => $this->setMyShare($transactionId, $myShareCents, $shares, $object, $notes),
                'record_debt' => $this->recordDebt($transactionId, $contactId, $amountCents, $object, $notes),
                'allocate' => $this->allocate($transactionId, $kind, $shareId, $amountCents, $contactId, $object, $notes),
                default => self::error("Unknown action: {$action}. Use list, summary, set_my_share, record_debt or allocate."),
            };
        } catch (MissingMcpUserException|\DomainException $e) {
            return self::error($e->getMessage());
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return self::error($cause->getMessage());
        }
    }

    private function list(?string $contactId, ?string $status, ?string $direction): string
    {
        $user = $this->userContext->requireUser();

        $contact = null;
        if (null !== $contactId) {
            $contact = Ulid::isValid($contactId) ? $this->contactRepository->findOneOwned($contactId, $user) : null;
            if (null === $contact) {
                return self::error("Contact not found: {$contactId}");
            }
        }
        $wanted = null === $status ? null : PeerDebtStatus::tryFrom($status);
        if (null !== $status && null === $wanted) {
            return self::error('status must be open or settled.');
        }

        $wantedDirection = null === $direction ? null : PeerDebtDirection::tryFrom($direction);
        if (null !== $direction && null === $wantedDirection) {
            return self::error('direction must be owed_to_me or i_owe.');
        }

        return self::json(['peerDebts' => $this->listPeerDebts->execute($user, $wanted, $wantedDirection, $contact)]);
    }

    /** @param list<mixed>|null $shares what the agent sent: checked one by one */
    private function setMyShare(?string $transactionId, ?int $myShareCents, ?array $shares, ?string $object, ?string $notes): string
    {
        if (null === $transactionId) {
            return self::error('transactionId is required for set_my_share.');
        }
        $userId = (string) $this->userContext->requireUser()->getId();

        if (null === $myShareCents) {
            $stamped = $this->bus->dispatch(new RemoveMyShareCommand(userId: $userId, transactionId: $transactionId));
            /** @var Transaction $transaction */
            $transaction = $stamped->last(HandledStamp::class)->getResult();

            return self::json(['success' => true, 'transactionId' => $transactionId, 'myShareCents' => $transaction->getMyShareCents(), 'peerDebt' => null]);
        }

        $parsed = [];
        foreach ($shares ?? [] as $share) {
            $contactId = \is_array($share) ? ($share['contactId'] ?? null) : null;
            $amountCents = \is_array($share) ? ($share['amountCents'] ?? null) : null;
            if (!\is_string($contactId) || (null !== $amountCents && !\is_int($amountCents))) {
                return self::error('shares is a list of {contactId, amountCents}.');
            }
            $parsed[] = ['contactId' => $contactId, 'amountCents' => $amountCents];
        }

        $stamped = $this->bus->dispatch(new SetMyShareCommand(
            userId: $userId,
            transactionId: $transactionId,
            myShareCents: $myShareCents,
            shares: $parsed,
            object: $object,
            notes: $notes,
        ));
        /** @var PeerDebt $debt */
        $debt = $stamped->last(HandledStamp::class)->getResult();

        return self::json([
            'success' => true,
            'transactionId' => $transactionId,
            'myShareCents' => $myShareCents,
            'peerDebt' => $debt->toArray(),
        ]);
    }

    private function recordDebt(?string $transactionId, ?string $contactId, ?int $amountCents, ?string $object, ?string $notes): string
    {
        if (null === $transactionId) {
            return self::error('transactionId is required for record_debt.');
        }
        $userId = (string) $this->userContext->requireUser()->getId();

        if (null === $amountCents) {
            $this->bus->dispatch(new RemoveDebtCommand(userId: $userId, transactionId: $transactionId));

            return self::json(['success' => true, 'transactionId' => $transactionId, 'peerDebt' => null]);
        }
        if (null === $contactId) {
            return self::error('contactId is required for record_debt: who sent the transfer.');
        }

        $stamped = $this->bus->dispatch(new RecordDebtCommand(
            userId: $userId,
            transactionId: $transactionId,
            contactId: $contactId,
            amountCents: $amountCents,
            object: $object,
            notes: $notes,
        ));
        /** @var PeerDebt $debt */
        $debt = $stamped->last(HandledStamp::class)->getResult();

        return self::json(['success' => true, 'transactionId' => $transactionId, 'peerDebt' => $debt->toArray()]);
    }

    /**
     * One allocation per call: a repayment keeps the transfer's other
     * allocations and sets this share's amount — 0 takes it off.
     */
    private function allocate(?string $transactionId, ?string $kind, ?string $shareId, ?int $amountCents, ?string $contactId, ?string $object, ?string $notes): string
    {
        if (null === $transactionId) {
            return self::error('transactionId is required for allocate.');
        }
        $user = $this->userContext->requireUser();
        $transaction = Ulid::isValid($transactionId) ? $this->transactionRepository->findOneBy(['id' => $transactionId, 'user' => $user]) : null;
        if (null === $transaction) {
            return self::error("Transaction not found: {$transactionId}");
        }
        $userId = (string) $user->getId();

        $allocations = [];
        if (PeerKind::Repayment->value === $kind) {
            if (null === $shareId || null === $amountCents) {
                return self::error('shareId and amountCents are required for a repayment: the share it gives back and how much.');
            }
            if (PeerKind::Repayment === $transaction->getPeerKind()) {
                foreach ($this->allocationRepository->findByTransaction($transaction) as $allocation) {
                    $allocations[(string) $allocation->getShare()->getId()] = $allocation->getAmountCents();
                }
            }
            $allocations[$shareId] = $amountCents;
            $allocations = array_filter($allocations, static fn (int $amount): bool => 0 !== $amount);
        }

        if (null === $kind || (PeerKind::Repayment->value === $kind && [] === $allocations)) {
            $this->bus->dispatch(new RemoveAllocationCommand(userId: $userId, transactionId: $transactionId));
        } else {
            $this->bus->dispatch(new AllocateTransactionCommand(
                userId: $userId,
                transactionId: $transactionId,
                kind: $kind,
                allocations: array_map(
                    static fn (string $id, int $amount): array => ['shareId' => $id, 'amountCents' => $amount],
                    array_keys($allocations),
                    array_values($allocations),
                ),
                debt: PeerKind::NewDebt->value === $kind
                    ? ['contactId' => $contactId, 'amountCents' => $amountCents ?? 0, 'object' => $object, 'notes' => $notes]
                    : null,
                claim: PeerKind::NewClaim->value === $kind ? ['contactId' => $contactId, 'object' => $object, 'notes' => $notes] : null,
            ));
        }

        return self::json(['success' => true, ...$this->getAllocation->execute($transaction)]);
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, JSON_THROW_ON_ERROR);
    }

    private static function error(string $message): string
    {
        return self::json(['error' => $message]);
    }
}
