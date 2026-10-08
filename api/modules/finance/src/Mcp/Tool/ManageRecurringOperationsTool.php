<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Message\CreateRecurringOperationCommand;
use Maggie\Finance\Message\DeleteRecurringOperationCommand;
use Maggie\Finance\Message\UpdateRecurringOperationCommand;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Service\RecurrenceSchedule;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_recurring_operations', description: 'List, create, update or delete the recurring operations: the series the user expects to see again (rent on the 5th, a subscription on the 12th, a salary), as opposed to the transactions, the real lines of the statement. Occurrences are computed, never stored. A series has a label, a categoryId and an accountId (its currency is the account\'s), the counterpartyName it is paid to or received from (the main way to recognise its transactions) and/or a labelPattern read in the label when there is no counterparty, a period (weekly, monthly, quarterly, yearly), anchorOn (ISO date of the first occurrence; every other one is counted from it), a dayRule (fixed_day: the anchor\'s day, clamped to short months; last_day_of_month), referenceAmountCents (signed integer cents, never 0: negative = expense, positive = income; the category must match: an income category only on a positive amount), referenceSource (measured: follows the attached transactions, the default; declared: stays as typed), amountTolerancePercent (0-100, 20 by default), dateToleranceDays (0-31, 5 by default) and endsOn (ISO date, optional, not before anchorOn). List gives each series with its monthlyCostCents, yearlyCostCents and nextOccurrenceOn (after today). On update, only provided fields change; to remove counterpartyName, labelPattern or endsOn, list it in clear.')]
class ManageRecurringOperationsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly RecurringOperationRepository $operationRepository,
        private readonly RecurrenceSchedule $schedule,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param list<string>|null $clear */
    public function __invoke(
        string $action,
        ?string $recurringOperationId = null,
        ?string $label = null,
        ?string $categoryId = null,
        ?string $accountId = null,
        ?int $referenceAmountCents = null,
        ?string $anchorOn = null,
        ?string $counterpartyName = null,
        ?string $labelPattern = null,
        ?string $period = null,
        ?string $dayRule = null,
        ?string $referenceSource = null,
        ?int $amountTolerancePercent = null,
        ?int $dateToleranceDays = null,
        ?string $endsOn = null,
        ?array $clear = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($label, $categoryId, $accountId, $referenceAmountCents, $anchorOn, $counterpartyName, $labelPattern, $period, $dayRule, $referenceSource, $amountTolerancePercent, $dateToleranceDays, $endsOn),
                'update' => $this->update($recurringOperationId, $label, $categoryId, $accountId, $referenceAmountCents, $anchorOn, $counterpartyName, $labelPattern, $period, $dayRule, $referenceSource, $amountTolerancePercent, $dateToleranceDays, $endsOn, $clear),
                'delete' => $this->delete($recurringOperationId),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, create, update, or delete."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        } catch (\ValueError $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function list(): string
    {
        $user = $this->userContext->requireUser();

        return json_encode([
            'recurringOperations' => array_map(
                fn (RecurringOperation $o) => $this->serialize($o),
                $this->operationRepository->findByUser($user),
            ),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $label, ?string $categoryId, ?string $accountId, ?int $referenceAmountCents, ?string $anchorOn, ?string $counterpartyName, ?string $labelPattern, ?string $period, ?string $dayRule, ?string $referenceSource, ?int $amountTolerancePercent, ?int $dateToleranceDays, ?string $endsOn): string
    {
        if (null === $label || null === $categoryId || null === $accountId || null === $referenceAmountCents || null === $anchorOn) {
            return json_encode(['error' => 'label, categoryId, accountId, referenceAmountCents and anchorOn are required for create.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $stamped = $this->bus->dispatch(new CreateRecurringOperationCommand(
            userId: (string) $user->getId(),
            label: $label,
            categoryId: $categoryId,
            accountId: $accountId,
            referenceAmountCents: $referenceAmountCents,
            anchorOn: $anchorOn,
            counterpartyName: $counterpartyName,
            labelPattern: $labelPattern,
            period: $period ?? 'monthly',
            dayRule: $dayRule ?? 'fixed_day',
            referenceSource: $referenceSource ?? 'measured',
            amountTolerancePercent: $amountTolerancePercent ?? RecurringOperation::DEFAULT_AMOUNT_TOLERANCE_PERCENT,
            dateToleranceDays: $dateToleranceDays ?? RecurringOperation::DEFAULT_DATE_TOLERANCE_DAYS,
            endsOn: $endsOn,
        ));

        /** @var RecurringOperation $operation */
        $operation = $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'recurringOperation' => $this->serialize($operation),
        ], JSON_THROW_ON_ERROR);
    }

    /** @param list<string>|null $clear */
    private function update(?string $recurringOperationId, ?string $label, ?string $categoryId, ?string $accountId, ?int $referenceAmountCents, ?string $anchorOn, ?string $counterpartyName, ?string $labelPattern, ?string $period, ?string $dayRule, ?string $referenceSource, ?int $amountTolerancePercent, ?int $dateToleranceDays, ?string $endsOn, ?array $clear): string
    {
        if (null === $recurringOperationId) {
            return json_encode(['error' => 'recurringOperationId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $stamped = $this->bus->dispatch(new UpdateRecurringOperationCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            recurringOperationId: $recurringOperationId,
            label: $label,
            categoryId: $categoryId,
            accountId: $accountId,
            referenceAmountCents: $referenceAmountCents,
            anchorOn: $anchorOn,
            counterpartyName: $counterpartyName,
            labelPattern: $labelPattern,
            period: $period,
            dayRule: $dayRule,
            referenceSource: $referenceSource,
            amountTolerancePercent: $amountTolerancePercent,
            dateToleranceDays: $dateToleranceDays,
            endsOn: $endsOn,
            clearFields: array_values(array_intersect($clear ?? [], ['counterpartyName', 'labelPattern', 'endsOn'])),
        ));

        /** @var RecurringOperation $operation */
        $operation = $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'recurringOperation' => $this->serialize($operation),
        ], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $recurringOperationId): string
    {
        if (null === $recurringOperationId) {
            return json_encode(['error' => 'recurringOperationId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteRecurringOperationCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            recurringOperationId: $recurringOperationId,
        ));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function serialize(RecurringOperation $operation): array
    {
        $next = $this->schedule->nextOccurrenceAfter($operation, new \DateTimeImmutable('today'));

        return [
            'id' => (string) $operation->getId(),
            'label' => $operation->getLabel(),
            'counterpartyName' => $operation->getCounterpartyName(),
            'labelPattern' => $operation->getLabelPattern(),
            'period' => $operation->getPeriod()->value,
            'anchorOn' => $operation->getAnchorOn()->format('Y-m-d'),
            'dayRule' => $operation->getDayRule()->value,
            'referenceAmountCents' => $operation->getReferenceAmountCents(),
            'referenceSource' => $operation->getReferenceSource()->value,
            'amountTolerancePercent' => $operation->getAmountTolerancePercent(),
            'dateToleranceDays' => $operation->getDateToleranceDays(),
            'endsOn' => $operation->getEndsOn()?->format('Y-m-d'),
            'monthlyCostCents' => $operation->getMonthlyCostCents(),
            'yearlyCostCents' => $operation->getYearlyCostCents(),
            'nextOccurrenceOn' => $next?->format('Y-m-d'),
            'categoryId' => (string) $operation->getCategory()->getId(),
            'categoryName' => $operation->getCategory()->getName(),
            'accountId' => (string) $operation->getAccount()->getId(),
            'accountName' => $operation->getAccount()->getName(),
            'currency' => $operation->getAccount()->getCurrency(),
        ];
    }
}
