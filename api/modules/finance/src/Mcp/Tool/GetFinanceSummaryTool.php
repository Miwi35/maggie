<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetFinanceScores;
use Maggie\Finance\UseCase\GetIndependenceCounter;
use Maggie\Finance\UseCase\GetMonthBalance;
use Maggie\Finance\UseCase\GetPeriodBalance;
use Maggie\Finance\UseCase\GetReferenceSalary;
use Maggie\Finance\UseCase\GetSalaryDelay;
use Maggie\Finance\UseCase\GetSavingsAdvances;
use Maggie\Finance\UseCase\PlanningPeriod;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_finance_summary', description: 'Reads the financial state of a period (period: YYYY-MM, default current month, or YYYY). spending: every debit against what was planned, with the daily curve. envelopes: consumed vs budgeted. satisfaction: worth keeping vs avoidable. savings: put aside vs planned capacity. cushion. toQualify: unqualified lines. scores: week, month and global colours with their reasons (null for a year). salary: reference monthly salary, its source (declared, expected, measured, unknown) and the delay. advances: money taken from savings, outstanding per source. independence: how far the rentes cover the lifestyle. Integer cents.')]
class GetFinanceSummaryTool
{
    public function __construct(
        private readonly GetMonthBalance $getMonthBalance,
        private readonly GetPeriodBalance $getPeriodBalance,
        private readonly GetFinanceScores $getFinanceScores,
        private readonly GetReferenceSalary $getReferenceSalary,
        private readonly GetSalaryDelay $getSalaryDelay,
        private readonly GetSavingsAdvances $getSavingsAdvances,
        private readonly GetIndependenceCounter $getIndependenceCounter,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?string $period = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $period ??= (new \DateTimeImmutable('today'))->format('Y-m');

        if (1 === preg_match('/^(20\d{2}|2100)$/', $period)) {
            $base = $this->getPeriodBalance->execute($user, PlanningPeriod::ofYear((int) $period));
            $scores = null;
        } elseif (1 === preg_match('/^(20\d{2}|2100)-(0[1-9]|1[0-2])$/', $period)) {
            $base = $this->getMonthBalance->execute($user, new \DateTimeImmutable($period.'-01'));
            $day = $this->getFinanceScores->dateInMonth($user, (int) substr($period, 0, 4), (int) substr($period, 5, 2));
            $scores = $this->getFinanceScores->execute($user, $day);
        } else {
            return json_encode(['error' => 'period must be a month as YYYY-MM or a year as YYYY, between 2000 and 2100'], JSON_THROW_ON_ERROR);
        }

        return json_encode(
            ['period' => $period] + $base + [
                'scores' => $scores,
                'salary' => $this->getReferenceSalary->execute($user) + ['delay' => $this->getSalaryDelay->execute($user)],
                'advances' => $this->getSavingsAdvances->execute($user),
                'independence' => $this->getIndependenceCounter->execute($user),
            ],
            JSON_THROW_ON_ERROR,
        );
    }
}
