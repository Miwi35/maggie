<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Exception\InvalidHealthProfileException;
use Maggie\Health\Message\UpdateHealthProfileCommand;
use Maggie\Health\Service\HealthProfileReader;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'update_health_profile', description: 'Change the user\'s health profile (Santé › Configuration): only the arguments given change, the others keep their value; give at least one. sex: male or female. birthYear: the year of birth (age = current year − birthYear; « j\'ai 38 ans » in 2026 is 1988). heightCm: whole centimetres, 100 to 250. weightKg: kilograms, more than 0 and at most 500 — recorded as a manual weighing taken now, listed in Santé › Suivi. activityLevel: sedentary, light, moderate, active or very_active. mode: computed (the day\'s need is the basal metabolism × the activity factor, rounded to 10 kcal) or entered (the day\'s need is enteredNeedKcal). enteredNeedKcal: whole kcal, 1000 to 5000, by steps of 50. stepGoal: steps a day, 500 to 50000, by steps of 500 (« mon objectif de pas, c\'est 10 000 » is 10000). suggestionWindowStart and suggestionWindowEnd: « HH:MM », the end after the start — when Maggie may suggest a session. sessionMinutes: 15, 20 or 30. stepSource: health_connect or none. Returns {"success": true, "profile": …} with the profile as get_health_profile reads it, or {"error", "field"} naming the refused field; nothing is changed then.')]
final class UpdateHealthProfileTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly HealthProfileReader $reader,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        #[Schema(enum: ['male', 'female'])]
        ?string $sex = null,
        ?int $birthYear = null,
        ?int $heightCm = null,
        ?float $weightKg = null,
        #[Schema(enum: ['sedentary', 'light', 'moderate', 'active', 'very_active'])]
        ?string $activityLevel = null,
        #[Schema(enum: ['computed', 'entered'])]
        ?string $mode = null,
        ?int $enteredNeedKcal = null,
        ?int $stepGoal = null,
        ?string $suggestionWindowStart = null,
        ?string $suggestionWindowEnd = null,
        #[Schema(enum: [15, 20, 30])]
        ?int $sessionMinutes = null,
        #[Schema(enum: ['health_connect', 'none'])]
        ?string $stepSource = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();

            $given = array_filter(
                compact('sex', 'birthYear', 'heightCm', 'weightKg', 'activityLevel', 'mode', 'enteredNeedKcal', 'stepGoal', 'suggestionWindowStart', 'suggestionWindowEnd', 'sessionMinutes', 'stepSource'),
                static fn (mixed $value): bool => null !== $value,
            );
            if ([] === $given) {
                return json_encode(['error' => 'Give at least one field to change.', 'current' => $this->reader->describe($user)], JSON_THROW_ON_ERROR);
            }

            $this->bus->dispatch(new UpdateHealthProfileCommand(
                userId: (string) $user->getId(),
                sex: $sex,
                birthYear: $birthYear,
                heightCm: $heightCm,
                weightKg: $weightKg,
                activityLevel: $activityLevel,
                mode: $mode,
                enteredNeedKcal: $enteredNeedKcal,
                stepGoal: $stepGoal,
                suggestionWindowStart: $suggestionWindowStart,
                suggestionWindowEnd: $suggestionWindowEnd,
                sessionMinutes: $sessionMinutes,
                stepSource: $stepSource,
            ));

            return json_encode(['success' => true, 'profile' => $this->reader->describe($user)], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            if ($cause instanceof InvalidHealthProfileException) {
                return json_encode(['error' => $cause->getMessage(), 'field' => $cause->field], JSON_THROW_ON_ERROR);
            }
            if ($cause instanceof \DomainException) {
                return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
            }

            throw $e;
        }
    }
}
