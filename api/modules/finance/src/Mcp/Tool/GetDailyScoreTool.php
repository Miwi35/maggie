<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\UseCase\GetDailyScore;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_daily_score', description: 'Read the daily financial signal for a month (defaults to the current one): green, neutral, orange or red, with the reasons behind it. Red means the month\'s total budget is over, or a mandatory category is; orange means a non-mandatory category is over, or planned spending would take it over; green additionally requires a complete safety cushion and spending below the same month last year; neutral is everything else, including having no envelope at all. Reasons come as codes with amounts in cents (mandatory_category_exceeded, optional_category_exceeded, plans_exceed_category_budget, total_budget_exceeded, cushion_incomplete, below_last_year, above_last_year, no_budget) — phrase them for the user rather than reading them out.')]
class GetDailyScoreTool
{
    public function __construct(
        private readonly GetDailyScore $getDailyScore,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?int $year = null, ?int $month = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $now = new \DateTimeImmutable();

        return json_encode($this->getDailyScore->execute(
            $user,
            $year ?? (int) $now->format('Y'),
            $month ?? (int) $now->format('n'),
        ), JSON_THROW_ON_ERROR);
    }
}
