<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Entity\User;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Enum\HealthDayAuthor;
use Maggie\Health\Exception\InvalidHealthDayException;
use Maggie\Health\Message\UpdateHealthDayCommand;
use Maggie\Health\Service\HealthDayReader;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'update_health_day', description: 'Writes a day of the health journal: date (YYYY-MM-DD) and at least one of mood (1-10, 10 best), fatigue (1-10, 10 most tired), note (max 2000 characters; blank removes it). Only arguments given change; a missing day is created, marked as written by Maggie. Returns {success, day} or {error, field, current}.')]
final class UpdateHealthDayTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly HealthDayReader $reader,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $date, ?int $mood = null, ?int $fatigue = null, ?string $note = null): string
    {
        try {
            $user = $this->userContext->requireUser();
            $this->bus->dispatch(new UpdateHealthDayCommand(
                userId: (string) $user->getId(),
                date: $date,
                author: HealthDayAuthor::Maggie,
                mood: $mood,
                fatigue: $fatigue,
                note: $note,
            ));

            return json_encode(['success' => true, 'day' => $this->reader->day($user, $date)], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            if ($cause instanceof InvalidHealthDayException) {
                return json_encode(['error' => $cause->getMessage(), 'field' => $cause->field, 'current' => self::current($this->reader, $user, $date)], JSON_THROW_ON_ERROR);
            }
            if ($cause instanceof \DomainException) {
                return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
            }

            throw $e;
        }
    }

    /** @return array<string, mixed>|null the day as it is, null for a date that is not one */
    private static function current(HealthDayReader $reader, User $user, string $date): ?array
    {
        try {
            return $reader->day($user, $date);
        } catch (\InvalidArgumentException) {
            return null;
        }
    }
}
