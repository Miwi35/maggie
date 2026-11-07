<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Service\DailyEnergyReader;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_daily_energy', description: 'Read the user\'s energy expenditure day by day (Santé › Suivi), from and to included (YYYY-MM-DD, at most 400 days). Returns {days: [...]}, one per local day, each {date, steps, activeKcal, bmrKcal, workoutKcal, stepsKcal, expendedKcal}. steps and activeKcal are Health Connect\'s of the day (null without data), already de-duplicated; bmrKcal is the basal metabolism (null while the health profile is incomplete); workoutKcal is the kcal of the day\'s done sessions; stepsKcal is activeKcal minus the sessions Health Connect already counted, never negative; expendedKcal = bmrKcal + activeKcal, plus the sessions Health Connect has not seen (a session typed by hand, kcal or MET × weight × duration) — sessions are never added twice. expendedKcal is null while bmrKcal is.')]
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
