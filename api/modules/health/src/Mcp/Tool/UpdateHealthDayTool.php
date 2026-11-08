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

#[McpTool(name: 'update_health_day', description: 'Write what the user told you about their day in the health journal (Santé › Journal): date (YYYY-MM-DD, the local day the answer is about — the evening review is today), and at least one of mood (how they feel, a whole number 1 to 10, 10 the best), fatigue (how tired they are, a whole number 1 to 10, 10 the most tired) and note (free text in their own words, at most 2000 characters; a blank note removes it). Only the arguments given change; a day not in the journal yet is laid down. The entry shows « avec Maggie » and the time. Never invent a score: ask for it. Returns {"success": true, "day": …} as get_health_day reads it, or {"error", "field", "current"} naming the refused field with the day as it is; nothing is changed then.')]
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
