<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Service\TrainingHistory;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_training_status', description: 'Reads the strength program status, date YYYY-MM-DD. Returns {date, programWeek (from the first done calibration session), phase (calibration weeks 1-2, ramp_up 3-4, normal), deload {due, reason (scheduled, stagnation, hard_sessions, null), weeksSinceLast}, stagnating (slugs without gain for over 14 days), week {planned, done}, hardSetsByPattern}. Invalid date: {error}.')]
final class GetTrainingStatusTool
{
    public function __construct(
        private readonly TrainingHistory $history,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $date): string
    {
        try {
            return json_encode($this->history->status($this->userContext->requireUser(), $date), JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
