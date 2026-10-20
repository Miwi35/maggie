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

#[McpTool(name: 'check_routine', description: 'Tick a routine as done on a day, or take the tick back. id is the routine\'s (list_routines gives it — call it with the date first when the user names the routine by its title). date is YYYY-MM-DD, today (the user\'s timezone) when left out; a day to come, or before the routine existed, is refused. done=false removes the tick. Ticking twice or removing a tick that is not there changes nothing. A routine may be ticked on a day it was not due. Returns {success, date, checked, routine} as list_routines reads the routine. Say it is noted, nothing more: no congratulations, no streak.')]
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
