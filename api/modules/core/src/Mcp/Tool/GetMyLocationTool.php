<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Core\Repository\UserLocationRepository;
use Mcp\Capability\Attribute\McpTool;
use Psr\Clock\ClockInterface;

#[McpTool(name: 'get_my_location', description: 'Where the phone last was; saved places are list_places. Reads the user\'s phone last GPS position. Returns {position}: {latitude, longitude, accuracy (metres), capturedAt, ageMinutes}, or null when the phone never reported a GPS point (the position must be allowed in the app). Only GPS positions are stored, and the last one stays the position whatever its age.')]
final class GetMyLocationTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly UserLocationRepository $locations,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], \JSON_THROW_ON_ERROR);
        }

        $location = $this->locations->findOneByUser($user);
        $now = $this->clock->now();

        return json_encode(['position' => $location?->getFix()?->toArray($now)], \JSON_THROW_ON_ERROR);
    }
}
