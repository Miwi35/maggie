<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Service\TrainingHistory;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_training_status', description: 'Where the user stands in the strength program on one day (Santé › Sport), date YYYY-MM-DD. Returns {date, programWeek, phase, deload, stagnating, week, hardSetsByPattern}: programWeek counts from the first calibration session done (null before it); phase calibration (weeks 1-2), ramp_up (3-4) or normal; deload {due, reason scheduled (8 weeks) | stagnation (2 exercises or more stagnating) | hard_sessions (difficulty 9 or more twice in the week) | null, weeksSinceLast}: when due, plan the week\'s sessions with variant deload; stagnating the slugs without gain for more than 14 days; week {planned, done} the strength sessions of the Monday-Sunday week; hardSetsByPattern the sets done that week close to failure, per movement pattern. Invalid date: {error}.')]
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
