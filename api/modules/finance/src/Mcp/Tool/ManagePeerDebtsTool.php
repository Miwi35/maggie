<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\PeerDebtStatus;
use Maggie\Finance\Message\RemoveMyShareCommand;
use Maggie\Finance\Message\SetMyShareCommand;
use Maggie\Finance\Repository\ContactRepository;
use Maggie\Finance\Repository\PeerDebtRepository;
use Maggie\Finance\UseCase\GetPeerDebtSummary;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'manage_peer_debts', description: 'Money between the owner and the people around them (créances: they owe the owner; dettes: the owner owes them). Not bank loans (manage_loans). Actions: list (every créance and dette, newest first, each with its shares per person — contactId, contactName, amountCents, repaidCents, remainingCents —, amountCents, remainingCents and status open or settled; optional contactId to keep those of one person, optional status open or settled), summary (owedToMeCents: what people still owe the owner, iOweCents: what the owner still owes), set_my_share (a spend paid for others too, e.g. a 800 € gîte for four: transactionId, myShareCents — the owner\'s own part, 0 ≤ part < the amount; only it counts in the envelopes, the score, the review and the lifestyle —, shares: [{contactId, amountCents}] — the people who owe the rest, contacts from manage_contacts; leave every amountCents out to split equally, or give them all adding up to the amount minus the part —, optional object (what it was for, the label by default) and notes; setting it again updates the same créance; myShareCents null removes the share and its créance, refused once someone gave money back on it). Only a spend can be shared, not an income nor an internal transfer.')]
class ManagePeerDebtsTool
{
    private const ACTIONS = ['list', 'summary', 'set_my_share'];

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly PeerDebtRepository $peerDebtRepository,
        private readonly ContactRepository $contactRepository,
        private readonly GetPeerDebtSummary $summary,
        private readonly McpUserContext $userContext,
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
    ): string {
        try {
            return match ($action) {
                'list' => $this->list($contactId, $status),
                'summary' => self::json($this->summary->execute($this->userContext->requireUser())),
                'set_my_share' => $this->setMyShare($transactionId, $myShareCents, $shares, $object, $notes),
                default => self::error("Unknown action: {$action}. Use list, summary or set_my_share."),
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
