<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Core\Repository\UserLocationRepository;
use Maggie\Core\Service\UserPositions;
use Mcp\Capability\Attribute\McpTool;
use Psr\Clock\ClockInterface;

#[McpTool(name: 'get_my_location', description: 'Read where the user\'s phone last was. Returns {position, gps, network}: gps and network are the last position of each source, {source, latitude, longitude, accuracy (metres), capturedAt, ageMinutes} or null; position is the one to trust, a copy of gps or of network. The GPS position is the default; the network one (Wi-Fi and cell towers, which can be kilometres off) stands only when there is no GPS position, or when the GPS one is older than 2 hours and the network one is newer. Say how old the position is (ageMinutes) and where it comes from before relying on it. All null when the phone never reported: the user has to allow the position in the app.')]
final class GetMyLocationTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly UserLocationRepository $locations,
        private readonly UserPositions $positions,
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
        $chosen = null === $location ? null : $this->positions->chosen($location);

        return json_encode([
            'position' => $chosen?->toArray($now),
            'gps' => $location?->getGpsFix()?->toArray($now),
            'network' => $location?->getNetworkFix()?->toArray($now),
        ], \JSON_THROW_ON_ERROR);
    }
}
