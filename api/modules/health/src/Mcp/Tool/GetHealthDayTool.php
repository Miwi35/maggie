<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Service\HealthDayReader;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_health_day', description: 'Read the user\'s health journal entry of one local day (Santé › Journal), date as YYYY-MM-DD. Returns {day: {id, date, weightKg, bodyFatPercent, weighedAt, steps, eatenKcal, expendedKcal, balanceKcal, sportMinutes, mood, fatigue, note, notedBy, notedAt}}. weightKg and bodyFatPercent come from the day\'s first weighing not set aside, at weighedAt; null without a weighing that day (never the day before\'s). steps are Health Connect\'s; eatenKcal what was recorded as eaten; expendedKcal the consolidated expenditure; sportMinutes the done sessions\' minutes; any of them null when nothing was recorded. balanceKcal is eatenKcal − expendedKcal, except today: the live balance, planned meals included. mood and fatigue are scored 1 to 10, note is free text, notedBy (user or maggie) and notedAt say who wrote them last and when. A day with nothing recorded has id null and every figure null.')]
final class GetHealthDayTool
{
    public function __construct(
        private readonly HealthDayReader $reader,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $date): string
    {
        try {
            return json_encode(['day' => $this->reader->day($this->userContext->requireUser(), $date)], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
