<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Message\ApplyPeriodPlanCommand;
use Maggie\Finance\UseCase\PlanningPeriod;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_period_plan', description: 'Write one element of the plan of a period, once the owner has decided it (read the plan first with plan_annual_budget, which takes the same period). period is a year (2027), a quarter (2027-Q2) or a semester (2027-S1). One call writes one element; repeat the call for the next one. Actions: set_annual_envelope (categoryId, amountCents; the period must be a year) sets the annual envelope of a category; set_monthly_envelope (categoryId, amountCents) sets the same monthly envelope on every month of the period, and seasonFromMonth + seasonToMonth + seasonAmountCents together (1-12) give those months of the year another amount — one season per call, which replaces the seasons of those envelopes; set_one_off_envelope (name, amountCents) sets the envelope of an event with no category, found by its name; set_event (categoryId, label, amountCents, month as YYYY-MM inside the period, optional accountId) plans an expense, booked on the 15th of that month on the first checking account unless accountId says otherwise. amountCents is positive, in cents; currency defaults to EUR. Replaying a call updates what it wrote and never duplicates; an expense the owner already committed is left alone. The envelopes and planned expenses written are the plan: nothing else is stored, and the calendar is never touched.')]
class ManagePeriodPlanTool
{
    private const ACTIONS = ['set_annual_envelope', 'set_monthly_envelope', 'set_one_off_envelope', 'set_event'];

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        #[Schema(enum: self::ACTIONS)]
        string $action,
        ?string $period = null,
        ?string $categoryId = null,
        ?int $amountCents = null,
        ?string $currency = null,
        ?string $name = null,
        ?string $label = null,
        ?string $month = null,
        ?string $accountId = null,
        ?int $seasonFromMonth = null,
        ?int $seasonToMonth = null,
        ?int $seasonAmountCents = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();

            if (null === $period) {
                return self::error('period is required: a year (2027), a quarter (2027-Q1) or a semester (2027-S2).');
            }
            $planned = PlanningPeriod::parse($period);

            $item = match ($action) {
                'set_annual_envelope' => ['kind' => 'annual', 'categoryId' => $categoryId, 'amountCents' => $amountCents],
                'set_monthly_envelope' => ['kind' => 'monthly', 'categoryId' => $categoryId, 'amountCents' => $amountCents]
                    + self::seasons($seasonFromMonth, $seasonToMonth, $seasonAmountCents),
                'set_one_off_envelope' => ['kind' => 'one_off', 'name' => $name, 'amountCents' => $amountCents],
                'set_event' => ['kind' => 'event', 'categoryId' => $categoryId, 'label' => $label, 'amountCents' => $amountCents, 'month' => $month]
                    + (null === $accountId ? [] : ['accountId' => $accountId]),
                default => throw new \InvalidArgumentException("Unknown action: {$action}. Use set_annual_envelope, set_monthly_envelope, set_one_off_envelope or set_event."),
            };
            if (null !== $currency) {
                $item['currency'] = $currency;
            }

            $applied = $this->bus->dispatch(new ApplyPeriodPlanCommand((string) $user->getId(), $planned->key(), [$item]))
                ->last(HandledStamp::class)?->getResult();

            return json_encode(['success' => true, 'period' => $planned->describe(), 'applied' => $applied[0]], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return self::error($e->getMessage());
        } catch (HandlerFailedException $e) {
            return self::error(($e->getPrevious() ?? $e)->getMessage());
        }
    }

    /** @return array{seasons?: list<array{fromMonth: int, toMonth: int, amountCents: int}>} */
    private static function seasons(?int $from, ?int $to, ?int $amountCents): array
    {
        if (null === $from && null === $to && null === $amountCents) {
            return [];
        }
        if (null === $from || null === $to || null === $amountCents) {
            throw new \InvalidArgumentException('A season needs seasonFromMonth, seasonToMonth and seasonAmountCents together.');
        }

        return ['seasons' => [['fromMonth' => $from, 'toMonth' => $to, 'amountCents' => $amountCents]]];
    }

    private static function error(string $message): string
    {
        return json_encode(['error' => $message], JSON_THROW_ON_ERROR);
    }
}
