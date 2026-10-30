<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Service\HealthProfileReader;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_health_profile', description: 'Read the user\'s health profile (Santé › Configuration). Returns sex (male or female), birthYear and age (current year minus birthYear), heightCm, weightKg (the latest weighing not set aside, read from Santé › Suivi), activityLevel (sedentary × 1.2, light × 1.375, moderate × 1.55, active × 1.725, very_active × 1.9), mode (computed: the day\'s need is computed; entered: the user typed it in enteredNeedKcal), bmrKcal (basal metabolism, Mifflin-St Jeor), needKcal (the day\'s need, the reference of the recipes\' kcal gauges), factor (needKcal ÷ bmrKcal), and the physical activity settings: stepGoal (steps a day), suggestionWindowStart and suggestionWindowEnd (« HH:MM », when Maggie may suggest a session), sessionMinutes (15, 20 or 30) and stepSource (health_connect or none). bmrKcal and needKcal are null while sex, age, height or weight is missing. id is null while the profile has never been written.')]
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
