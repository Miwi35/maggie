<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Core\Repository\UserLocationRepository;
use Mcp\Capability\Attribute\McpTool;
use Psr\Clock\ClockInterface;

#[McpTool(name: 'get_my_location', description: 'Read where the user\'s phone last was. Returns {position}: {latitude, longitude, accuracy (metres), capturedAt, ageMinutes} or null. Only GPS positions are kept (a network position, which can be kilometres off, is never stored), and the last GPS point stays the position whatever its age: a phone that has not moved for hours still has its last GPS point. Say how old the position is (ageMinutes) before relying on it. null when the phone never reported a GPS point: the user has to allow the position in the app.')]
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
