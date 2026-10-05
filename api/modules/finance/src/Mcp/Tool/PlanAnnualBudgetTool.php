<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Entity\User;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\ApplyAnnualPlan;
use Maggie\Finance\UseCase\GetAnnualPlan;
use Maggie\Finance\UseCase\PlanningYear;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;

#[McpTool(name: 'plan_annual_budget', description: 'Run the yearly planning session: look at the big expenses of the year behind, plan the expenses of the year ahead, and set the annual envelopes they add up to. The review action lists every category the year behind cost something, biggest decision first, and reports for each what it was budgeted and really consumed, its big expenses one by one (the debits over thresholdCents, 10000 by default — the threshold decides which expenses are listed, never which categories are in the session), what the target year has already planned and committed, what is only to arbitrate (reported, never counted), the annual envelope already set, and a suggested amount: what the target year has already decided on if anything, otherwise what the year behind consumed. year is between 2000 and 2100 and defaults to the one being prepared, which is next year in November and December and the current one the rest of the time. The schedule action plans one expense: categoryId, label, amountCents as a positive amount in cents, month 1-12 (the transaction lands on the 1st, because a planning session knows a month and not a day), status planned, committed or to_arbitrate (planned by default; spent is refused — you do not plan what has already gone out), accountId, and isExceptional, false by default, which the owner sets on a genuine one-off so it stays out of the measured everyday lifestyle. The budget action sets the annual envelope of one category for the year: categoryId and amountCents; it overwrites the amount already there, because the session is where the amount is decided. Schedule the expenses one at a time, then budget one envelope per category. This never touches the calendar: a planned expense is a transaction, not an appointment.')]
class PlanAnnualBudgetTool
{
    public function __construct(
        private readonly GetAnnualPlan $getAnnualPlan,
        private readonly ApplyAnnualPlan $applyAnnualPlan,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?int $year = null,
        ?int $thresholdCents = null,
        ?string $categoryId = null,
        ?string $label = null,
        ?int $amountCents = null,
        ?int $month = null,
        ?string $status = null,
        ?string $accountId = null,
        ?string $currency = null,
        ?bool $isExceptional = null,
    ): string {
        try {
            // Resolved before the action is read: a call nobody is bound to
            // must say so, whatever it was asking for.
            $user = $this->userContext->requireUser();

            return match ($action) {
                'review' => $this->review($user, $year, $thresholdCents),
                'schedule' => $this->schedule($user, $year, [
                    'categoryId' => $categoryId,
                    'label' => $label,
                    'amountCents' => $amountCents,
                    'month' => $month,
                    'status' => $status,
                    'currency' => $currency,
                    'isExceptional' => $isExceptional,
                ], $accountId),
                'budget' => $this->budget($user, $year, [
                    'categoryId' => $categoryId,
                    'amountCents' => $amountCents,
                    'currency' => $currency,
                ]),
                default => $this->fail("Unknown action: {$action}. Use review, schedule or budget."),
            };
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return $this->fail($e->getMessage());
        } catch (HandlerFailedException $e) {
            return $this->fail(($e->getPrevious() ?? $e)->getMessage());
        } catch (\ValueError $e) {
            return $this->fail($e->getMessage());
        }
    }

    private function review(User $user, ?int $year, ?int $thresholdCents): string
    {
        return $this->encode($this->getAnnualPlan->execute(
            $user,
            $this->resolveYear($year),
            $thresholdCents ?? GetAnnualPlan::DEFAULT_THRESHOLD_CENTS,
        ));
    }

    /** @param array<string, mixed> $event */
    private function schedule(User $user, ?int $year, array $event, ?string $accountId): string
    {
        // One expense at a time: that is how a list gets dictated, and it keeps
        // the tool free of array parameters.
        $result = $this->applyAnnualPlan->execute(
            $user,
            $this->resolveYear($year),
            [$this->without($event)],
            [],
            $accountId,
        );

        return $this->encode(['success' => true, 'event' => $result['events'][0]]);
    }

    /** @param array<string, mixed> $envelope */
    private function budget(User $user, ?int $year, array $envelope): string
    {
        $result = $this->applyAnnualPlan->execute(
            $user,
            $this->resolveYear($year),
            [],
            [$this->without($envelope)],
        );

        return $this->encode(['success' => true, 'envelope' => $result['envelopes'][0]]);
    }

    /**
     * Drops the parameters nobody passed, so the use case applies its own
     * defaults instead of reading a null as a value.
     *
     * @param array<string, mixed> $fields
     *
     * @return array<string, mixed>
     */
    private function without(array $fields): array
    {
        return array_filter($fields, static fn (mixed $value): bool => null !== $value);
    }

    private function resolveYear(?int $year): int
    {
        return $year ?? PlanningYear::default(new \DateTimeImmutable());
    }

    private function fail(string $message): string
    {
        return $this->encode(['error' => $message]);
    }

    /** @param array<string, mixed> $payload */
    private function encode(array $payload): string
    {
        return json_encode($payload, JSON_THROW_ON_ERROR);
    }
}
