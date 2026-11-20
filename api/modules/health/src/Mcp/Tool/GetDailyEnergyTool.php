<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Service\DailyEnergyReader;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_daily_energy', description: 'Reads energy expenditure per day, from and to included (YYYY-MM-DD, max 400 days). Returns {days}: per local day {date, steps, activeKcal (Health Connect), bmrKcal, workoutKcal, stepsKcal (activeKcal minus sessions Health Connect counted), expendedKcal = bmrKcal + activeKcal + sessions Health Connect did not see}. Null without data; bmrKcal and expendedKcal null while the profile is incomplete.')]
final class GetDailyEnergyTool
{
    public function __construct(
        private readonly DailyEnergyReader $reader,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $from, string $to): string
    {
        try {
            return json_encode(['days' => $this->reader->read($this->userContext->requireUser(), $from, $to)], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
