<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetFinanceScores;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_finance_scores', description: 'Reads the budget scores for a date (date: YYYY-MM-DD, default today): week, month and global (the worse of month and year). Each is green ("Dans le vert"), neutral ("Dans les clous"), orange ("Attention") or red ("Dépassement"), with reasons (codes, amounts in cents, e.g. mandatory_category_exceeded, no_budget). Colour comes from envelopes only; the week is always neutral. Each week and month has spentCents (every real debit to date), expectedSpentCents (planned to date), plannedCents (whole period), the daily curve, planned debits and unclassifiedCount / unclassifiedCents (debits without category, included in spentCents).')]
class GetFinanceScoresTool
{
    public function __construct(
        private readonly GetFinanceScores $getFinanceScores,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?string $date = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        try {
            $day = $this->getFinanceScores->resolveDate($user, $date);
        } catch (\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        return json_encode($this->getFinanceScores->execute($user, $day), JSON_THROW_ON_ERROR);
    }
}
