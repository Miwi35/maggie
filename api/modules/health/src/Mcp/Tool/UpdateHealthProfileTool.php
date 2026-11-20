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

#[McpTool(name: 'update_health_profile', description: 'Changes the health profile; only arguments given change, at least one. sex (male, female), birthYear, heightCm (100-250), weightKg (max 500 (also recorded as a weighing)), activityLevel (sedentary, light, moderate, active, very_active), mode (computed, entered), enteredNeedKcal (1000-5000), stepGoal (500-50000), suggestionWindowStart, suggestionWindowEnd (HH:MM, end after start), sessionMinutes (15, 20, 30), stepSource (health_connect, none), fastingProtocol (14:10, 16:8, 18:6, 20:4, none), fastingWindowStart (HH:MM). Returns {success, profile} or {error, field}; nothing changes then.')]
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
        #[Schema(enum: ['14:10', '16:8', '18:6', '20:4', 'none'])]
        ?string $fastingProtocol = null,
        ?string $fastingWindowStart = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();

            $given = array_filter(
                compact('sex', 'birthYear', 'heightCm', 'weightKg', 'activityLevel', 'mode', 'enteredNeedKcal', 'stepGoal', 'suggestionWindowStart', 'suggestionWindowEnd', 'sessionMinutes', 'stepSource', 'fastingProtocol', 'fastingWindowStart'),
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
                fastingProtocol: $fastingProtocol,
                fastingWindowStart: $fastingWindowStart,
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
