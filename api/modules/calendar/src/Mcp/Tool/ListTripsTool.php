<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\TripJson;
use Maggie\Calendar\Repository\TripRepository;
use Maggie\Calendar\Service\TripDay;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'list_trips', description: 'Read the trips kept with set_trip that leave on a day — date is YYYY-MM-DD, read in Paris — by departure time, as {date, trips: [...]}, each as set_trip returns it. Call it before planning the trips of a day, to know which events already have one and whether the user chose its origin (originDecidedBy user).')]
final class ListTripsTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly TripRepository $repository,
    ) {
    }

    public function __invoke(string $date): string
    {
        try {
            $user = $this->userContext->requireUser();
            [$from, $to] = TripDay::bounds($date);
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        } catch (\InvalidArgumentException $e) {
            return self::json(['error' => $e->getMessage(), 'field' => 'date']);
        }

        return self::json([
            'date' => $date,
            'trips' => array_map(TripJson::of(...), $this->repository->findLeavingBetween($user, $from, $to)),
        ]);
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, JSON_THROW_ON_ERROR);
    }
}
