<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetMonthBalance;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_month_balance', description: 'Reads a month\'s balance (month: YYYY-MM, 2000-2100, default current): spending (every debit, classified or not, planned recurring amounts, daily curve of both), envelopes (each root envelope: consumed vs budgeted, status held, close or over), satisfaction (worth keeping vs avoidable, kept share in percent, null while nothing is judged), savings (put aside vs planned capacity, null without known income), cushion, toQualify (count and weight of unqualified lines, in no envelope). Integer cents.')]
class GetMonthBalanceTool
{
    public function __construct(
        private readonly GetMonthBalance $getMonthBalance,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?string $month = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $month ??= (new \DateTimeImmutable('today'))->format('Y-m');
        if (1 !== preg_match('/^(20\d{2}|2100)-(0[1-9]|1[0-2])$/', $month)) {
            return json_encode(['error' => 'month must be a month as YYYY-MM, between 2000 and 2100'], JSON_THROW_ON_ERROR);
        }

        return json_encode($this->getMonthBalance->execute($user, new \DateTimeImmutable($month.'-01')), JSON_THROW_ON_ERROR);
    }
}
