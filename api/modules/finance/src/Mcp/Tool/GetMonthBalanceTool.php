<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetMonthBalance;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_month_balance', description: 'Read the balance of a month (YYYY-MM, defaults to the current one): `spending` (the month status, what was spent — every debit, classified or not —, what was planned ahead — the recurring operations — and the daily curve of both; spending more than planned is normal), `envelopes` (each root envelope with consumed against budgeted and its status held, close or over), `satisfaction` (what was judged worth keeping against avoidable, and the kept share in percent, null while nothing is judged), `savings` (what was put aside against the planned saving capacity, null when no income is known), `cushion`, and `toQualify` (the count and weight of the lines of the month nobody qualified yet — they belong to no envelope). All amounts are integer cents.')]
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
