<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Service\HealthDayReader;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_health_day', description: 'Reads one local day of the health journal, date YYYY-MM-DD. Returns {day: {id, date, weightKg, bodyFatPercent, weighedAt (first weighing of the day not set aside), steps, eatenKcal, expendedKcal, balanceKcal (eaten - expended; today live, planned meals included), sportMinutes, mood, fatigue (1-10), note, notedBy (user, maggie), notedAt}}. Null when nothing is recorded; an empty day has id null.')]
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
