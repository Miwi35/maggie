<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Service\HealthProfileReader;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_health_profile', description: 'Reads the health profile. Returns {id (null if never written), sex, birthYear, age, heightCm, weightKg (latest weighing not set aside), activityLevel (sedentary x1.2 to very_active x1.9), mode (computed, entered), bmrKcal, needKcal, factor (need / bmr), stepGoal, suggestionWindowStart, suggestionWindowEnd, sessionMinutes, stepSource}. bmrKcal, needKcal null while sex, age, height or weight is missing.')]
final class GetHealthProfileTool
{
    public function __construct(
        private readonly HealthProfileReader $reader,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(): string
    {
        try {
            return json_encode($this->reader->describe($this->userContext->requireUser()), JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
