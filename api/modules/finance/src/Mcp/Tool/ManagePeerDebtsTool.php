<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\Transaction;
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
use Maggie\Finance\Repository\PeerDebtRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\GetPeerDebtSummary;
use Maggie\Finance\UseCase\GetTransactionAllocation;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'manage_peer_debts', description: 'Money between the owner and the people around them (créances: they owe the owner; dettes: the owner owes them). Not bank loans (manage_loans). Actions: list (every créance and dette, newest first, each with its shares per person — contactId, contactName, amountCents, repaidCents, remainingCents —, amountCents, remainingCents and status open or settled; optional contactId to keep those of one person, optional status open or settled), summary (owedToMeCents: what people still owe the owner, iOweCents: what the owner still owes), set_my_share (a spend paid for others too, e.g. a 800 € gîte for four: transactionId, myShareCents — the owner\'s own part, 0 ≤ part < the amount; only it counts in the envelopes, the score, the review and the lifestyle —, shares: [{contactId, amountCents}] — the people who owe the rest, contacts from manage_contacts; leave every amountCents out to split equally, or give them all adding up to the amount minus the part —, optional object (what it was for, the label by default) and notes; setting it again updates the same créance; myShareCents null removes the share and its créance, refused once someone gave money back on it). Only a spend can be shared, not an income nor an internal transfer. record_debt (a transfer received that must be given back in part, e.g. Tom advances 400 € of which 280 € are his: transactionId — a received transfer, neither a spend nor an internal transfer —, contactId — the person it comes from, the transaction\'s contact is the natural one —, amountCents — what to give back, 0 < amount ≤ the transfer —, optional object and notes) records a dette (direction i_owe, one share) that is neither an income nor a saving: the rest of the transfer stays an income; declaring it again updates the same dette; amountCents null removes it, refused once part of it was given back. allocate (what a transfer with a person is — transactionId, kind: for a received transfer income (all of it stays an income), repayment (it gives back créances) or new_debt (money to give back: contactId, amountCents, optional object and notes, like record_debt); for a sent transfer repayment (it gives back dettes), new_claim (money lent: a créance of the whole amount to contactId, optional object and notes) or other (a plain spend). One allocation per call: for repayment give shareId — a share from list, of a créance for a received transfer, of a dette for a sent one, those of the transfer\'s contact first — and amountCents, at most what is left on that share; the transfer\'s other allocations are kept, amountCents 0 takes that share off, and all of them add up to at most the transfer. What is not allocated of a received transfer stays an income. An allocated part is neither an income nor a spend. kind null undoes the answer and gives back what it repaid. Never an internal transfer.) Returns the transfer\'s peerKind, allocations, unallocatedCents and the candidate shares.')]
class ManagePeerDebtsTool
{
    private const ACTIONS = ['list', 'summary', 'set_my_share', 'record_debt', 'allocate'];

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly PeerDebtRepository $peerDebtRepository,
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
                'list' => $this->list($contactId, $status),
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

    private function list(?string $contactId, ?string $status): string
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

        $debts = array_filter(
            $this->peerDebtRepository->findByUser($user, $contact),
            static fn (PeerDebt $debt): bool => null === $wanted || $debt->getStatus() === $wanted,
        );

        return self::json(['peerDebts' => array_map(static fn (PeerDebt $debt): array => $debt->toArray(), array_values($debts))]);
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
