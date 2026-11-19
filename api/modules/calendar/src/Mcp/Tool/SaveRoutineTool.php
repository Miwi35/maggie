<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Routine;
use Maggie\Calendar\Mcp\RoutineJson;
use Maggie\Calendar\Message\CreateRoutineCommand;
use Maggie\Calendar\Message\UpdateRoutineCommand;
use Maggie\Calendar\Repository\RoutineRepository;
use Maggie\Calendar\Service\RoutineSchedule;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'save_routine', description: 'Creates a routine (without id; title, anchor, recurrence required) or changes one (with id; given fields only). title: 255 characters max. anchor: wake, afterBreakfast, afterLunch, afterDinner, or time (needs anchorTime HH:MM, only this anchor). recurrence: {kind: daily}, {kind: weekdays}, {kind: weekly, weekday: 1 Monday to 7 Sunday} or {kind: monthly, monthDay: 1 to 31}. active=false pauses; remind=true asks a reminder. Refused call changes nothing, answers {error}. Returns {success, routine}.')]
final class SaveRoutineTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly RoutineRepository $repository,
        private readonly RoutineSchedule $schedule,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** @param array<string, mixed>|null $recurrence */
    public function __invoke(
        ?string $id = null,
        ?string $title = null,
        #[Schema(enum: Routine::ANCHORS)]
        ?string $anchor = null,
        ?string $anchorTime = null,
        #[Schema(type: 'object', properties: ['kind' => ['type' => 'string', 'enum' => Routine::KINDS], 'weekday' => ['type' => 'integer', 'minimum' => 1, 'maximum' => 7], 'monthDay' => ['type' => 'integer', 'minimum' => 1, 'maximum' => 31]], required: ['kind'])]
        ?array $recurrence = null,
        ?bool $active = null,
        ?bool $remind = null,
    ): string {
        $fields = array_filter(
            ['title' => $title, 'anchor' => $anchor, 'anchorTime' => $anchorTime, 'recurrence' => $recurrence, 'active' => $active, 'remind' => $remind],
            static fn (mixed $value): bool => null !== $value,
        );

        try {
            $user = $this->userContext->requireUser();
            $routine = null;

            if (null !== $id) {
                $routine = $this->repository->findOwned($user, $id);
                if (null === $routine) {
                    return self::json(['error' => "Routine not found: {$id}"]);
                }
                if ([] === $fields) {
                    return self::json(['error' => 'Nothing to change: give at least one of title, anchor, anchorTime, recurrence, active, remind.', 'routine' => $this->read($routine)]);
                }
            } else {
                $missing = array_values(array_filter(['title', 'anchor', 'recurrence'], static fn (string $field): bool => !isset($fields[$field])));
                if ([] !== $missing) {
                    return self::json([
                        'error' => 'A routine needs a title, an anchor and a recurrence; missing: '.implode(', ', $missing).'. Nothing was created — ask the user.',
                        'missing' => $missing,
                        'given' => $fields,
                    ]);
                }
            }

            try {
                $envelope = $this->bus->dispatch(null === $routine
                    ? new CreateRoutineCommand((string) $user->getId(), $fields)
                    : new UpdateRoutineCommand((string) $user->getId(), (string) $routine->getId(), $fields));
            } catch (HandlerFailedException $e) {
                // A refusal is Maggie's to read; anything else is ours.
                $cause = $e->getPrevious() ?? $e;
                if (!$cause instanceof \DomainException) {
                    throw $e;
                }

                return self::json(['error' => $cause->getMessage()] + (null === $routine ? ['given' => $fields] : ['routine' => $this->read($routine)]));
            }

            /** @var Routine $saved */
            $saved = $envelope->last(HandledStamp::class)->getResult();

            return self::json(['success' => true, 'routine' => $this->read($saved)]);
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        }
    }

    /** @return array<string, mixed> */
    private function read(Routine $routine): array
    {
        return RoutineJson::of($this->schedule->describe($routine));
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, JSON_THROW_ON_ERROR);
    }
}
