<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Exception\InvalidQualificationException;
use Maggie\Finance\Mcp\QualificationArguments;
use Maggie\Finance\Message\CreateTransactionCommand;
use Maggie\Finance\Message\DeleteTransactionCommand;
use Maggie\Finance\Message\SettleRejectionCommand;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\GetTransactionsToSort;
use Maggie\Finance\UseCase\SortTransaction;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'manage_transactions', description: 'List, create, update, delete, or categorize transactions; to_sort and sort work through the pile of lines that still wait for a category. List returns the newest first, one page at a time (limit, 30 by default, 100 at most) with total, the number of lines matching in all: to look further back or for something precise, narrow with categoryId (the category and its sub-categories; with limit 1, the latest line of that category), direction (expense or income), fromDate and toDate (ISO dates, both included), accountId, or query (text found in the label or in the counterparty, i.e. the creditor or debtor the bank names) instead of asking for more. List leaves the rejected payments out (a rejected debit and the credit that gave it back, which the account page shows apart as incidents): pass transferKind rejected to list only them, e.g. to count the direct debits rejected this month (each rejection is two lines, the debit and the credit), or internal or none to list only those (advance, repayment and funding each list only their own). Each line carries its label and its counterpartyName. Amounts are signed integer cents: negative = expense/debit, positive = income/credit; any category may be used on either sign. bookedAt is an ISO date (defaults to today). status is one of spent, committed, planned, to_arbitrate. On update, only provided fields change; to remove the category, list categoryId in clear. transferKind says whether the line is a neutral movement. internal is a movement between two of the user\'s own accounts, with counterpartId naming the other leg (omit it when only one of the two accounts is known), or none to take it back out of the transfers — list transferKind in clear for the same effect. transferKind rejected marks a payment the bank rejected, with counterpartId naming the credit that gave it back on the same account (the detection pairs them as they arrive: a credit worded REJET, IMPAYE or RETOUR PRLV with the debit of the same payee and amount). transferKind advance is an internal transfer from a savings or investment account to a checking one made while the salary was late, which the detection marks (get_savings_advances says what is left to give back); repayment is the transfer that gives an advance back, from the checking account to the source: the detection marks it when its amount equals exactly what is left of an open advance of that source (a partial amount is never reconciled), and by hand it takes counterpartId plus repaysTransactionId, the id of the advance (the debit leg get_savings_advances lists), set on the debit leg, the one leaving the checking account. funding is the user\'s regular top-up of a savings account, which the detection never takes for a repayment whatever its amount: mark it with transferKind funding and counterpartId (or alone when only one account is known); it stays a neutral internal transfer. propose_advance_repayments says what to transfer to give the advances back. An internal transfer or a rejection counts neither as an expense nor as an income; transferNote says it in the words the user sees (« Virement interne », « Rejeté » on the rejected payment, « Rejet de … » on the credit). A marking made here is recorded as the user\'s own decision, which the detection never overwrites; detect_internal_transfers is what pairs a whole history. recurringOperationId names the recurring operation the line is an occurrence of, and recurringOccurrenceOn (ISO date) the due date it settles: a line is attached automatically when its counterparty, its date and its amount all match a free occurrence. On update, recurringOperationId attaches the line by hand (to recurringOccurrenceOn, or to the due date nearest its bookedAt), recurringOccurrenceOn alone moves it to another due date of its series, and recurringOperation in clear detaches it; a gesture made here is recorded as the user\'s own (recurringSource manual), which the automatic attachment never overwrites. Attaching gives the line the whole qualification of the recurring operation (categoryId, mandatory, kind), unless the user qualified it by hand. note is the user\'s free text about the line (2000 characters at most): set it on create or update, a blank note or note in clear removes it, and an update that omits it leaves it alone. oneOffEnvelopeId attributes a spend by hand to a one-off envelope (an event such as Hellfest 2026, created with manage_envelopes mode one_off): the line keeps its category but leaves the monthly and annual envelopes and counts in the one-off one only; an empty oneOffEnvelopeId or oneOffEnvelope in clear takes the attribution back. Action to_sort lists the lines to sort one by one (limit, 50 by default): no category and not a transfer or a rejection, newest first, with total and, on each, a suggestion {categoryId, categoryPath, source rule|merchant, reason} or null. Action sort answers one of them: transactionId plus categoryId (the category is set by hand; alwaysForMerchant true also writes a \'contains\' rule on the first words of the label and files the other lines of the pile it claims) or transferKind internal (optional counterpartId) when the line moves money between the user\'s own accounts; a line that already has a category or a marking is refused. Action settle_rejection (transactionId: either leg of a rejected payment) records that the user dealt with it (paid again by other means), so it leaves the account incidents, the pile of rejections still to deal with; unsettle_rejection puts it back; any other line is refused. Each line carries rejectionSettledAt (ISO date-time, null while unsettled). qualifiedBy says who qualified a line: manual (the user, by hand: what is set here), series (qualifiedBySeriesId) or rule (qualifiedByRuleId); a change to that series or rule reaches the line, never once it is qualified by hand. retrospect is the owner\'s satisfaction with a spend, given once per line when it is reviewed: keep (« À conserver »), avoidable (« Superflue ») or unrated; set it on update, it is refused on an income or on a transfer between the user\'s own accounts. to_sort and sort also carry judgeable (true on a spend) and retrospect on each line, and to_sort totalCents, the sum of the amounts still to qualify.'.QualificationArguments::DESCRIPTION)]
class ManageTransactionsTool
{
    private const DEFAULT_LIMIT = 30;
    private const MAX_LIMIT = 100;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly TransactionRepository $transactionRepository,
        private readonly CategoryRepository $categoryRepository,
        private readonly McpUserContext $userContext,
        private readonly GetTransactionsToSort $getTransactionsToSort,
        private readonly SortTransaction $sortTransaction,
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
        ?string $transferKind = null,
        ?string $counterpartId = null,
        ?string $recurringOperationId = null,
        ?string $recurringOccurrenceOn = null,
        ?array $clear = null,
        ?int $limit = null,
        ?string $fromDate = null,
        ?string $toDate = null,
        ?string $query = null,
        ?string $direction = null,
        ?string $repaysTransactionId = null,
        ?string $note = null,
        ?string $oneOffEnvelopeId = null,
        ?bool $mandatory = null,
        ?string $kind = null,
        ?bool $alwaysForMerchant = null,
        ?string $retrospect = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list($accountId, $limit, $fromDate, $toDate, $query, $direction, $transferKind, $categoryId),
                'create' => $this->create($accountId, $amountCents, $label, $bookedAt, $status, $currency, $isExceptional, $categoryId, $note, ['mandatory' => $mandatory, 'kind' => $kind]),
                'update' => $this->update($transactionId, $accountId, $amountCents, $label, $bookedAt, $status, $currency, $isExceptional, $categoryId, $transferKind, $counterpartId, $recurringOperationId, $recurringOccurrenceOn, $clear, $repaysTransactionId, $note, $oneOffEnvelopeId, ['mandatory' => $mandatory, 'kind' => $kind], $retrospect),
                'categorize' => $this->categorize($transactionId, $categoryId),
                'to_sort' => $this->toSort($limit),
                'sort' => $this->sort($transactionId, $categoryId, $alwaysForMerchant ?? false, $transferKind, $counterpartId),
                'settle_rejection' => $this->settleRejection($transactionId, true),
                'unsettle_rejection' => $this->settleRejection($transactionId, false),
                'delete' => $this->delete($transactionId),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, create, update, categorize, to_sort, sort, settle_rejection, unsettle_rejection, or delete."], JSON_THROW_ON_ERROR),
            };
        } catch (InvalidQualificationException $e) {
            return json_encode($e->toResponse(), JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        } catch (\ValueError $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function list(?string $accountId, ?int $limit, ?string $fromDate, ?string $toDate, ?string $query, ?string $direction, ?string $transferKind, ?string $categoryId): string
    {
        $user = $this->userContext->requireUser();

        if (null !== $accountId && !Ulid::isValid($accountId)) {
            return json_encode(['error' => 'accountId is not a valid identifier.'], JSON_THROW_ON_ERROR);
        }
        if (null !== $direction && !in_array($direction, ['expense', 'income'], true)) {
            return json_encode(['error' => 'direction is expense or income.'], JSON_THROW_ON_ERROR);
        }
        $kind = null === $transferKind ? null : TransferKind::tryFrom($transferKind);
        if (null !== $transferKind && null === $kind) {
            return json_encode(['error' => 'transferKind is one of: '.implode(', ', array_column(TransferKind::cases(), 'value')).'.'], JSON_THROW_ON_ERROR);
        }
        $from = $this->parseDay($fromDate);
        $to = $this->parseDay($toDate);
        if ((null !== $fromDate && null === $from) || (null !== $toDate && null === $to)) {
            return json_encode(['error' => 'fromDate and toDate are ISO dates (YYYY-MM-DD).'], JSON_THROW_ON_ERROR);
        }

        $categoryIds = null;
        if (null !== $categoryId) {
            $category = Ulid::isValid($categoryId) ? $this->categoryRepository->find(Ulid::fromString($categoryId)) : null;
            // A category of someone else is reported as unknown, not as forbidden.
            if (null === $category || !$category->getUser()->getId()->equals($user->getId())) {
                return json_encode(['error' => 'categoryId does not match any of your categories.'], JSON_THROW_ON_ERROR);
            }
            $categoryIds = $this->categoryRepository->findSelfAndDescendantIds($category);
        }

        $page = $this->transactionRepository->searchByUser(
            $user,
            null === $limit || $limit < 1 ? self::DEFAULT_LIMIT : min($limit, self::MAX_LIMIT),
            $accountId,
            $from,
            $to,
            $query,
            $direction,
            $kind,
            $categoryIds,
        );

        return json_encode([
            'transactions' => array_map(fn (Transaction $t) => $this->serialize($t), $page['transactions']),
            'total' => $page['total'],
        ], JSON_THROW_ON_ERROR);
    }

    private function parseDay(?string $day): ?\DateTimeImmutable
    {
        if (null === $day) {
            return null;
        }

        $parsed = \DateTimeImmutable::createFromFormat('!Y-m-d', $day);

        return false !== $parsed && $parsed->format('Y-m-d') === $day ? $parsed : null;
    }

    /** @param array<string, mixed> $qualification */
    private function create(?string $accountId, ?int $amountCents, ?string $label, ?string $bookedAt, ?string $status, ?string $currency, ?bool $isExceptional, ?string $categoryId, ?string $note, array $qualification): string
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
            note: $note,
            qualification: QualificationArguments::whole($qualification),
        ));

        /** @var Transaction $transaction */
        $transaction = $envelope->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'transaction' => $this->serialize($transaction),
        ], JSON_THROW_ON_ERROR);
    }

    /**
     * @param list<string>|null    $clear
     * @param array<string, mixed> $qualification
     */
    private function update(?string $transactionId, ?string $accountId, ?int $amountCents, ?string $label, ?string $bookedAt, ?string $status, ?string $currency, ?bool $isExceptional, ?string $categoryId, ?string $transferKind, ?string $counterpartId, ?string $recurringOperationId, ?string $recurringOccurrenceOn, ?array $clear, ?string $repaysTransactionId = null, ?string $note = null, ?string $oneOffEnvelopeId = null, array $qualification = [], ?string $retrospect = null): string
    {
        if (null === $transactionId) {
            return json_encode(['error' => 'transactionId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();
        $current = $this->transactionRepository->findOneBy(['id' => $transactionId, 'user' => $user]);

        // Fails loudly on an unknown kind rather than silently leaving the line alone.
        $kind = null === $transferKind ? null : TransferKind::from($transferKind)->value;

        // Same: fails loudly on an unknown satisfaction.
        $verdict = null === $retrospect ? null : RetrospectVerdict::from($retrospect)->value;

        $clearFields = array_values(array_intersect($clear ?? [], ['categoryId', 'transferKind', 'recurringOperation', 'note', 'oneOffEnvelope']));
        // A null argument cannot be told from an omitted one: an empty id takes the attribution back.
        if ('' === $oneOffEnvelopeId) {
            $oneOffEnvelopeId = null;
            $clearFields[] = 'oneOffEnvelope';
        }

        $envelope = $this->bus->dispatch(new UpdateTransactionCommand(
            userId: (string) $user->getId(),
            transactionId: $transactionId,
            accountId: $accountId,
            amountCents: $amountCents,
            label: $label,
            bookedAt: $bookedAt,
            status: $status,
            currency: $currency,
            isExceptional: $isExceptional,
            categoryId: $categoryId,
            qualification: QualificationArguments::whole($qualification, $current?->getQualification()),
            retrospect: $verdict,
            // A marking made through the tool is the user speaking: the
            // detection leaves `manual` alone for good.
            transferKind: $kind,
            transferSource: null === $kind ? null : TransferSource::Manual->value,
            counterpartId: $counterpartId,
            repaysTransactionId: $repaysTransactionId,
            recurringOperationId: $recurringOperationId,
            recurringOccurrenceOn: $recurringOccurrenceOn,
            note: $note,
            oneOffEnvelopeId: $oneOffEnvelopeId,
            clearFields: $clearFields,
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

    private function toSort(?int $limit): string
    {
        $user = $this->userContext->requireUser();

        return json_encode($this->getTransactionsToSort->execute(
            $user,
            null === $limit || $limit < 1 ? GetTransactionsToSort::DEFAULT_LIMIT : $limit,
        ), JSON_THROW_ON_ERROR);
    }

    private function sort(?string $transactionId, ?string $categoryId, bool $alwaysForMerchant, ?string $transferKind, ?string $counterpartId): string
    {
        if (null === $transactionId) {
            return json_encode(['error' => 'transactionId is required for sort.'], JSON_THROW_ON_ERROR);
        }
        if ((null === $categoryId) === (null === $transferKind)) {
            return json_encode(['error' => 'sort takes either categoryId or transferKind internal, not both and not neither.'], JSON_THROW_ON_ERROR);
        }
        if (null !== $transferKind && TransferKind::Internal->value !== $transferKind) {
            return json_encode(['error' => 'sort only marks transferKind internal; use update for the other markings.'], JSON_THROW_ON_ERROR);
        }
        if (null !== $transferKind && $alwaysForMerchant) {
            return json_encode(['error' => 'alwaysForMerchant goes with a categoryId.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();
        $transaction = Ulid::isValid($transactionId)
            ? $this->transactionRepository->findOneBy(['id' => $transactionId, 'user' => $user])
            : null;
        if (null === $transaction) {
            return json_encode(['error' => "Transaction not found: {$transactionId}"], JSON_THROW_ON_ERROR);
        }

        try {
            if (null !== $categoryId) {
                $result = $this->sortTransaction->toCategory($user, $transaction, $categoryId, $alwaysForMerchant);

                return json_encode([
                    'success' => true,
                    'transaction' => $this->serialize($transaction),
                    'rule' => null === $result['rule'] ? null : ['id' => (string) $result['rule']->getId(), 'labelPattern' => $result['rule']->getLabelPattern()],
                    'ruleCreated' => $result['ruleCreated'],
                    'alsoSorted' => $result['alsoSorted'],
                    'remaining' => $result['remaining'],
                ], JSON_THROW_ON_ERROR);
            }

            $result = $this->sortTransaction->toInternalTransfer($user, $transaction, $counterpartId);

            return json_encode([
                'success' => true,
                'transaction' => $this->serialize($transaction),
                'remaining' => $result['remaining'],
            ], JSON_THROW_ON_ERROR);
        } catch (\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function settleRejection(?string $transactionId, bool $settled): string
    {
        $action = $settled ? 'settle_rejection' : 'unsettle_rejection';
        if (null === $transactionId) {
            return json_encode(['error' => "transactionId is required for {$action}."], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();
        $transaction = Ulid::isValid($transactionId)
            ? $this->transactionRepository->findOneBy(['id' => $transactionId, 'user' => $user])
            : null;
        if (null === $transaction) {
            return json_encode(['error' => "Transaction not found: {$transactionId}"], JSON_THROW_ON_ERROR);
        }

        // A line that is not a rejection is refused by the handler: HandlerFailedException, caught above.
        $envelope = $this->bus->dispatch(new SettleRejectionCommand((string) $transaction->getId(), $settled));
        /** @var Transaction $transaction */
        $transaction = $envelope->last(HandledStamp::class)?->getResult();

        return json_encode(['success' => true, 'transaction' => $this->serialize($transaction)], JSON_THROW_ON_ERROR);
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
            'counterpartyName' => $transaction->getCounterpartyName(),
            'amountCents' => $transaction->getAmountCents(),
            'currency' => $transaction->getCurrency(),
            'bookedAt' => $transaction->getBookedAt()->format('Y-m-d'),
            'status' => $transaction->getStatus()->value,
            'isExceptional' => $transaction->isExceptional(),
            'transferKind' => $transaction->getTransferKind()->value,
            'transferSource' => $transaction->getTransferSource()->value,
            'transferNote' => self::transferNote($transaction),
            'accountId' => (string) $transaction->getAccount()->getId(),
            'categoryId' => null !== $transaction->getCategory() ? (string) $transaction->getCategory()->getId() : null,
            'counterpartId' => null !== $transaction->getCounterpart() ? (string) $transaction->getCounterpart()->getId() : null,
            'rejectionSettledAt' => $transaction->getRejectionSettledAt()?->format(\DateTimeInterface::ATOM),
            'repaysTransactionId' => null !== $transaction->getRepays() ? (string) $transaction->getRepays()->getId() : null,
            'categorySource' => $transaction->getCategorySource()->value,
            ...$transaction->getQualification()->toArray(),
            'qualifiedBy' => $transaction->getQualifiedBy(),
            'qualifiedBySeriesId' => null !== $transaction->getQualifiedBySeries() ? (string) $transaction->getQualifiedBySeries()->getId() : null,
            'qualifiedByRuleId' => null !== $transaction->getQualifiedByRule() ? (string) $transaction->getQualifiedByRule()->getId() : null,
            'recurringOperationId' => null !== $transaction->getRecurringOperation() ? (string) $transaction->getRecurringOperation()->getId() : null,
            'recurringOccurrenceOn' => $transaction->getRecurringOccurrenceOn()?->format('Y-m-d'),
            'recurringSource' => $transaction->getRecurringSource()->value,
            'retrospect' => $transaction->getRetrospect()->value,
            'note' => $transaction->getNote(),
            'oneOffEnvelopeId' => null !== $transaction->getOneOffEnvelope() ? (string) $transaction->getOneOffEnvelope()->getId() : null,
        ];
    }

    /**
     * What the owner reads next to the line, the words the admin and the app
     * show: « Rejeté » on a rejected payment, « Rejet de … » on the credit
     * that gave it back.
     */
    private static function transferNote(Transaction $transaction): ?string
    {
        $counterpart = $transaction->getCounterpart();

        return match ($transaction->getTransferKind()) {
            TransferKind::None => null,
            TransferKind::Internal => 'Virement interne',
            TransferKind::Advance => 'Avance d\'épargne',
            TransferKind::Repayment => 'Remboursement d\'avance',
            TransferKind::Funding => 'Approvisionnement',
            TransferKind::Rejected => match (true) {
                $transaction->getAmountCents() < 0 => 'Rejeté',
                null === $counterpart => 'Rejet',
                default => sprintf('Rejet de %s du %s', $counterpart->getLabel(), $counterpart->getBookedAt()->format('Y-m-d')),
            },
        };
    }
}
