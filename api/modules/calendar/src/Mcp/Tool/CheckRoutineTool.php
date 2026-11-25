<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\RoutineJson;
use Maggie\Calendar\Message\CheckRoutineCommand;
use Maggie\Calendar\Repository\RoutineRepository;
use Maggie\Calendar\Service\DayPlans;
use Maggie\Calendar\Service\RoutineSchedule;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'check_routine', description: 'Ticks a routine as done on a day, or removes the tick (done=false). id is from list_routines. date is YYYY-MM-DD, today in the user\'s timezone by default; a future day or one before the routine existed is refused. Repeating a tick or removing an absent one changes nothing. Returns {success, date, checked, routine}. A task is ticked with update_task.')]
final class CheckRoutineTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly RoutineRepository $repository,
        private readonly RoutineSchedule $schedule,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(string $id, ?string $date = null, bool $done = true): string
    {
        try {
            $user = $this->userContext->requireUser();
            $routine = $this->repository->findOwned($user, $id);
            if (null === $routine) {
                return self::json(['error' => "Routine not found: {$id}"]);
            }
            $day = null !== $date ? DayPlans::day($date) : $this->schedule->today($user);

            $this->bus->dispatch(new CheckRoutineCommand((string) $user->getId(), $id, $day, $done));

            return self::json([
                'success' => true,
                'date' => $day->format('Y-m-d'),
                'checked' => $done,
                'routine' => RoutineJson::of($this->schedule->describe($routine)),
            ]);
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return self::json(['error' => $e->getMessage()]);
        } catch (HandlerFailedException $e) {
            // A refused day is Maggie's to read; anything else is ours.
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return self::json(['error' => $cause->getMessage()]);
        }
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, JSON_THROW_ON_ERROR);
    }
}
