<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Exception\InvalidQualificationException;
use Maggie\Finance\Mcp\QualificationArguments;
use Maggie\Finance\Message\ApplyOperationCommand;
use Maggie\Finance\Message\AttachRecurringOperationsCommand;
use Maggie\Finance\Message\CreateOperationCommand;
use Maggie\Finance\Message\DeleteOperationCommand;
use Maggie\Finance\Message\UpdateOperationCommand;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\MatchCriteria;
use Maggie\Finance\Service\RecurrenceSchedule;
use Maggie\Finance\UseCase\ApplyCategorizationRules;
use Maggie\Finance\UseCase\PreviewCategorizationRule;
use Maggie\Finance\UseCase\PreviewRecurringOperation;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_operations', description: 'Manage operations: criteria plus a qualification copied onto matching transactions; with a recurrence it is a series, otherwise a categorization rule. Criteria: counterpartyName, labelPattern + matchType contains|starts_with|equals, direction debit|credit, minAmountCents/maxAmountCents (absolute cents); none is refused with current state. Recurrence (fixed at creation; all four or none, else refused with current values): period, anchorOn ISO, referenceAmountCents (signed, not 0), accountId; plus label. list: recurring filter; series return monthlyCostCents, yearlyCostCents, nextOccurrenceOn. update: provided fields only; clear removes label, counterpartyName, labelPattern, minAmountCents, maxAmountCents, endsOn. preview: same criteria (operationId when editing), returns total, changeCount. create/update: applyToExisting (default false). apply: operationId, or all. learn: transactionId, categoryId. attach: operationId, transactionId; refused if occurrence taken or account, currency, sign differ. detach: transactionId. delete: operationId.'.QualificationArguments::DESCRIPTION)]
class ManageOperationsTool
{
    private const array RECURRENCE = ['period', 'anchorOn', 'referenceAmountCents', 'accountId'];

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly RecurringOperationRepository $operationRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly RecurrenceSchedule $schedule,
        private readonly ApplyCategorizationRules $applyCategorizationRules,
        private readonly PreviewCategorizationRule $previewWithoutRecurrence,
        private readonly PreviewRecurringOperation $previewSeries,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param list<string>|null $clear */
    public function __invoke(
        string $action,
        ?string $operationId = null,
        ?bool $recurring = null,
        ?string $categoryId = null,
        ?string $label = null,
        ?string $counterpartyName = null,
        ?string $labelPattern = null,
        ?string $matchType = null,
        ?string $direction = null,
        ?int $minAmountCents = null,
        ?int $maxAmountCents = null,
        ?int $priority = null,
        ?bool $isActive = null,
        ?string $period = null,
        ?string $anchorOn = null,
        ?int $referenceAmountCents = null,
        ?string $accountId = null,
        ?string $dayRule = null,
        ?string $referenceSource = null,
        ?int $amountTolerancePercent = null,
        ?int $dateToleranceDays = null,
        ?string $endsOn = null,
        ?array $clear = null,
        ?bool $applyToExisting = null,
        ?string $transactionId = null,
        ?string $recurringOccurrenceOn = null,
        ?bool $mandatory = null,
        ?string $kind = null,
    ): string {
        $fields = [
            'categoryId' => $categoryId,
            'label' => $label,
            'counterpartyName' => $counterpartyName,
            'labelPattern' => $labelPattern,
            'matchType' => $matchType,
            'direction' => $direction,
            'minAmountCents' => $minAmountCents,
            'maxAmountCents' => $maxAmountCents,
            'priority' => $priority,
            'isActive' => $isActive,
            'period' => $period,
            'anchorOn' => $anchorOn,
            'referenceAmountCents' => $referenceAmountCents,
            'accountId' => $accountId,
            'dayRule' => $dayRule,
            'referenceSource' => $referenceSource,
            'amountTolerancePercent' => $amountTolerancePercent,
            'dateToleranceDays' => $dateToleranceDays,
            'endsOn' => $endsOn,
        ];
        $qualification = ['mandatory' => $mandatory, 'kind' => $kind];

        try {
            return match ($action) {
                'list' => $this->list($recurring),
                'create' => $this->create($fields, $applyToExisting ?? false, $qualification),
                'update' => $this->update($operationId, $fields, $clear, $applyToExisting ?? false, $qualification),
                'delete' => $this->delete($operationId),
                'preview' => $this->preview($operationId, $fields),
                'apply' => $this->apply($operationId),
                'learn' => $this->learn($transactionId, $categoryId, $labelPattern, $matchType, $priority),
                'attach' => $this->attach($operationId, $transactionId, $recurringOccurrenceOn),
                'detach' => $this->detach($transactionId),
                default => self::error("Unknown action: {$action}. Use list, create, update, delete, preview, apply, learn, attach, or detach."),
            };
        } catch (InvalidQualificationException $e) {
            return json_encode($e->toResponse(), JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return self::error($e->getMessage());
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return self::error($cause->getMessage());
        } catch (\DomainException|\ValueError $e) {
            return self::error($e->getMessage());
        }
    }

    private function list(?bool $recurring): string
    {
        $user = $this->userContext->requireUser();
        $operations = match ($recurring) {
            true => $this->operationRepository->findSeriesByUser($user),
            false => $this->operationRepository->findWithoutRecurrenceByUser($user),
            null => $this->operationRepository->findByUser($user),
        };

        return json_encode([
            'operations' => array_map(fn (RecurringOperation $o) => $this->serialize($o), $operations),
        ], JSON_THROW_ON_ERROR);
    }

    /**
     * @param array<string, mixed> $fields
     * @param array<string, mixed> $qualification
     */
    private function create(array $fields, bool $applyToExisting, array $qualification): string
    {
        if (null === $fields['categoryId']) {
            return self::error('categoryId is required for create.');
        }

        if (null !== $partial = self::partialRecurrence($fields)) {
            return $partial;
        }

        $recurring = null !== $fields['period'];
        if (!$recurring && !$this->recognisable($fields)) {
            return json_encode(['error' => RecurringOperation::UNRECOGNISABLE, 'current' => self::recognition($fields)], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        /** @var RecurringOperation $operation */
        $operation = $this->bus->dispatch(new CreateOperationCommand(
            userId: (string) $user->getId(),
            categoryId: $fields['categoryId'],
            label: $fields['label'],
            counterpartyName: $fields['counterpartyName'],
            labelPattern: $fields['labelPattern'],
            matchType: $fields['matchType'] ?? 'contains',
            direction: $fields['direction'] ?? 'any',
            minAmountCents: $fields['minAmountCents'],
            maxAmountCents: $fields['maxAmountCents'],
            priority: $fields['priority'] ?? 0,
            isActive: $fields['isActive'] ?? true,
            period: $fields['period'],
            anchorOn: $fields['anchorOn'],
            referenceAmountCents: $fields['referenceAmountCents'],
            accountId: $fields['accountId'],
            dayRule: $fields['dayRule'] ?? 'fixed_day',
            referenceSource: $fields['referenceSource'] ?? 'measured',
            amountTolerancePercent: $fields['amountTolerancePercent'] ?? RecurringOperation::DEFAULT_AMOUNT_TOLERANCE_PERCENT,
            dateToleranceDays: $fields['dateToleranceDays'] ?? RecurringOperation::DEFAULT_DATE_TOLERANCE_DAYS,
            endsOn: $fields['endsOn'],
            applyToExisting: $applyToExisting,
            qualification: QualificationArguments::whole($qualification),
        ))->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'operation' => $this->serialize($operation)], JSON_THROW_ON_ERROR);
    }

    /**
     * @param array<string, mixed> $fields
     * @param list<string>|null    $clear
     * @param array<string, mixed> $qualification
     */
    private function update(?string $operationId, array $fields, ?array $clear, bool $applyToExisting, array $qualification): string
    {
        if (null === $operationId) {
            return self::error('operationId is required for update.');
        }

        $user = $this->userContext->requireUser();
        $before = $this->operationRepository->findOneBy(['id' => $operationId, 'user' => $user]);
        $current = null === $before ? null : $this->serialize($before);

        try {
            /** @var RecurringOperation $operation */
            $operation = $this->bus->dispatch(new UpdateOperationCommand(
                userId: (string) $user->getId(),
                operationId: $operationId,
                categoryId: $fields['categoryId'],
                label: $fields['label'],
                counterpartyName: $fields['counterpartyName'],
                labelPattern: $fields['labelPattern'],
                matchType: $fields['matchType'],
                direction: $fields['direction'],
                minAmountCents: $fields['minAmountCents'],
                maxAmountCents: $fields['maxAmountCents'],
                priority: $fields['priority'],
                isActive: $fields['isActive'],
                period: $fields['period'],
                anchorOn: $fields['anchorOn'],
                referenceAmountCents: $fields['referenceAmountCents'],
                accountId: $fields['accountId'],
                dayRule: $fields['dayRule'],
                referenceSource: $fields['referenceSource'],
                amountTolerancePercent: $fields['amountTolerancePercent'],
                dateToleranceDays: $fields['dateToleranceDays'],
                endsOn: $fields['endsOn'],
                clearFields: array_values(array_intersect($clear ?? [], UpdateOperationCommand::CLEARABLE)),
                applyToExisting: $applyToExisting,
                qualification: QualificationArguments::whole($qualification, $before?->getQualification()),
            ))->last(HandledStamp::class)->getResult();
        } catch (HandlerFailedException $e) {
            // Refused with the state it was refused against, so the next call can be right.
            if (null !== $current) {
                return json_encode(['error' => ($e->getPrevious() ?? $e)->getMessage(), 'current' => $current], JSON_THROW_ON_ERROR);
            }

            throw $e;
        }

        return json_encode(['success' => true, 'operation' => $this->serialize($operation)], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $operationId): string
    {
        if (null === $operationId) {
            return self::error('operationId is required for delete.');
        }

        $this->bus->dispatch(new DeleteOperationCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            operationId: $operationId,
        ));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /** @param array<string, mixed> $fields */
    private function preview(?string $operationId, array $fields): string
    {
        $user = $this->userContext->requireUser();

        $preview = null !== $fields['referenceAmountCents']
            ? $this->previewSeries->execute(
                user: $user,
                referenceAmountCents: $fields['referenceAmountCents'],
                counterpartyName: $fields['counterpartyName'],
                labelPattern: $fields['labelPattern'],
                amountTolerancePercent: $fields['amountTolerancePercent'] ?? RecurringOperation::DEFAULT_AMOUNT_TOLERANCE_PERCENT,
                accountId: $fields['accountId'],
            )
            : $this->previewWithoutRecurrence->execute(
                user: $user,
                labelPattern: $fields['labelPattern'],
                matchType: $fields['matchType'] ?? 'contains',
                direction: $fields['direction'] ?? 'any',
                minAmountCents: $fields['minAmountCents'],
                maxAmountCents: $fields['maxAmountCents'],
                categoryId: $fields['categoryId'],
                priority: $fields['priority'] ?? 0,
                isActive: $fields['isActive'] ?? true,
                ruleId: $operationId,
                counterpartyName: $fields['counterpartyName'],
            );

        return json_encode(['success' => true] + $preview, JSON_THROW_ON_ERROR);
    }

    private function apply(?string $operationId): string
    {
        $user = $this->userContext->requireUser();

        if (null !== $operationId) {
            if (null === $this->operationRepository->findOneBy(['id' => $operationId, 'user' => $user])) {
                return self::error("Operation not found: {$operationId}");
            }

            $result = $this->bus->dispatch(new ApplyOperationCommand($operationId))->last(HandledStamp::class)->getResult();

            return json_encode(['success' => true] + $result, JSON_THROW_ON_ERROR);
        }

        // The order a new line goes through: the series first, then the others.
        $attached = $this->bus->dispatch(new AttachRecurringOperationsCommand((string) $user->getId()))->last(HandledStamp::class)->getResult();
        $categorized = $this->applyCategorizationRules->execute($user);

        return json_encode([
            'success' => true,
            'attached' => $attached['attached'] ?? 0,
            'proposed' => $attached['proposed'] ?? 0,
        ] + $categorized, JSON_THROW_ON_ERROR);
    }

    /**
     * Turn a transaction into an operation without recurrence: file that
     * transaction under the category by hand, and remember the label so the
     * next one files itself.
     */
    private function learn(?string $transactionId, ?string $categoryId, ?string $labelPattern, ?string $matchType, ?int $priority): string
    {
        if (null === $transactionId || null === $categoryId) {
            return self::error('transactionId and categoryId are required for learn.');
        }

        $user = $this->userContext->requireUser();

        $transaction = $this->transactionRepository->find($transactionId);
        if (null === $transaction || !$transaction->getUser()->getId()->equals($user->getId())) {
            return self::error("Transaction not found: {$transactionId}");
        }

        $pattern = $labelPattern ?? self::patternFromLabel($transaction->getLabel());
        if ('' === $pattern) {
            return self::error('Could not derive a pattern from that transaction label; pass labelPattern explicitly.');
        }

        /** @var RecurringOperation $operation */
        $operation = $this->bus->dispatch(new CreateOperationCommand(
            userId: (string) $user->getId(),
            categoryId: $categoryId,
            labelPattern: $pattern,
            matchType: $matchType ?? 'contains',
            priority: $priority ?? 0,
        ))->last(HandledStamp::class)->getResult();

        $this->bus->dispatch(new UpdateTransactionCommand(
            userId: (string) $user->getId(),
            transactionId: $transactionId,
            categoryId: $categoryId,
        ));

        return json_encode(['success' => true, 'operation' => $this->serialize($operation)], JSON_THROW_ON_ERROR);
    }

    private function attach(?string $operationId, ?string $transactionId, ?string $recurringOccurrenceOn): string
    {
        if (null === $operationId || null === $transactionId) {
            return self::error('operationId and transactionId are required for attach.');
        }

        // The same door as a PATCH of the line: one place checks the
        // occurrence, seals the gesture `manual` and publishes both sides.
        return $this->updateTransaction(new UpdateTransactionCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            transactionId: $transactionId,
            recurringOperationId: $operationId,
            recurringOccurrenceOn: $recurringOccurrenceOn,
        ));
    }

    private function detach(?string $transactionId): string
    {
        if (null === $transactionId) {
            return self::error('transactionId is required for detach.');
        }

        return $this->updateTransaction(new UpdateTransactionCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            transactionId: $transactionId,
            clearFields: ['recurringOperation'],
        ));
    }

    private function updateTransaction(UpdateTransactionCommand $command): string
    {
        /** @var Transaction $transaction */
        $transaction = $this->bus->dispatch($command)->last(HandledStamp::class)->getResult();
        $operation = $transaction->getRecurringOperation();

        return json_encode([
            'success' => true,
            'transaction' => [
                'id' => (string) $transaction->getId(),
                'label' => $transaction->getLabel(),
                'amountCents' => $transaction->getAmountCents(),
                'bookedAt' => $transaction->getBookedAt()->format('Y-m-d'),
                'categoryId' => null !== $transaction->getCategory() ? (string) $transaction->getCategory()->getId() : null,
                'categorySource' => $transaction->getCategorySource()->value,
                ...$transaction->getQualification()->toArray(),
                'qualifiedBy' => $transaction->getQualifiedBy(),
                'recurringOperationId' => null !== $operation ? (string) $operation->getId() : null,
                'recurringOccurrenceOn' => $transaction->getRecurringOccurrenceOn()?->format('Y-m-d'),
                'recurringSource' => $transaction->getRecurringSource()->value,
            ],
            'operation' => null !== $operation ? $this->serialize($operation) : null,
        ], JSON_THROW_ON_ERROR);
    }

    /**
     * Bank labels carry noise — dates, card numbers, receipt ids. Keep the
     * leading words, which is what identifies the merchant.
     */
    public static function patternFromLabel(string $label): string
    {
        $normalized = trim(preg_replace('/\s+/u', ' ', $label) ?? '');
        if ('' === $normalized) {
            return '';
        }

        $words = [];
        foreach (explode(' ', $normalized) as $word) {
            // Stop at the first token that looks like a number, date or reference.
            if (1 === preg_match('/\d/u', $word)) {
                break;
            }
            $words[] = $word;
            if (3 === \count($words)) {
                break;
            }
        }

        return [] === $words ? $normalized : implode(' ', $words);
    }

    /**
     * A recurrence is given whole or not at all: a part of one is refused
     * with what is missing, nothing is deduced.
     *
     * @param array<string, mixed> $fields
     */
    private static function partialRecurrence(array $fields): ?string
    {
        $missing = array_values(array_filter(self::RECURRENCE, static fn (string $field) => null === $fields[$field]));
        if ([] === $missing || \count($missing) === \count(self::RECURRENCE)) {
            return null;
        }

        return json_encode([
            'error' => RecurringOperation::PARTIAL_RECURRENCE.' Missing: '.implode(', ', $missing).'.',
            'current' => array_intersect_key($fields, array_flip(self::RECURRENCE)),
        ], JSON_THROW_ON_ERROR);
    }

    /** @param array<string, mixed> $fields */
    private function recognisable(array $fields): bool
    {
        return (new MatchCriteria(
            counterpartyKey: $fields['counterpartyName'],
            labelPattern: $fields['labelPattern'],
            direction: AmountDirection::tryFrom($fields['direction'] ?? 'any') ?? AmountDirection::Any,
            minAmountCents: $fields['minAmountCents'],
            maxAmountCents: $fields['maxAmountCents'],
        ))->isRecognisable();
    }

    /**
     * @param array<string, mixed> $fields
     *
     * @return array<string, mixed>
     */
    private static function recognition(array $fields): array
    {
        return [
            'counterpartyName' => $fields['counterpartyName'],
            'labelPattern' => $fields['labelPattern'],
            'direction' => $fields['direction'] ?? 'any',
            'minAmountCents' => $fields['minAmountCents'],
            'maxAmountCents' => $fields['maxAmountCents'],
        ];
    }

    private static function error(string $message): string
    {
        return json_encode(['error' => $message], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function serialize(RecurringOperation $operation): array
    {
        $account = $operation->getAccount();
        $next = $operation->hasRecurrence() ? $this->schedule->nextOccurrenceAfter($operation, new \DateTimeImmutable('today')) : null;

        return [
            'id' => (string) $operation->getId(),
            'recurring' => $operation->hasRecurrence(),
            'label' => $operation->getLabel(),
            'counterpartyName' => $operation->getCounterpartyName(),
            'labelPattern' => $operation->getLabelPattern(),
            'matchType' => $operation->getMatchType()->value,
            'direction' => $operation->getDirection()->value,
            'minAmountCents' => $operation->getMinAmountCents(),
            'maxAmountCents' => $operation->getMaxAmountCents(),
            'priority' => $operation->getPriority(),
            'isActive' => $operation->isActive(),
            'period' => $operation->getPeriod()?->value,
            'anchorOn' => $operation->getAnchorOn()?->format('Y-m-d'),
            'dayRule' => $operation->hasRecurrence() ? $operation->getDayRule()->value : null,
            'referenceAmountCents' => $operation->getReferenceAmountCents(),
            'referenceSource' => $operation->hasRecurrence() ? $operation->getReferenceSource()->value : null,
            'amountTolerancePercent' => $operation->hasRecurrence() ? $operation->getAmountTolerancePercent() : null,
            'dateToleranceDays' => $operation->hasRecurrence() ? $operation->getDateToleranceDays() : null,
            'endsOn' => $operation->getEndsOn()?->format('Y-m-d'),
            'monthlyCostCents' => $operation->getMonthlyCostCents(),
            'yearlyCostCents' => $operation->getYearlyCostCents(),
            'nextOccurrenceOn' => $next?->format('Y-m-d'),
            'categoryId' => (string) $operation->getCategory()->getId(),
            'categoryName' => $operation->getCategory()->getName(),
            ...$operation->getQualification()->toArray(),
            'accountId' => null !== $account ? (string) $account->getId() : null,
            'accountName' => $account?->getName(),
            'currency' => $account?->getCurrency(),
        ];
    }
}
