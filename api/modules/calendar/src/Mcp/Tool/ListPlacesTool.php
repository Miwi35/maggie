<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\PlaceJson;
use Maggie\Calendar\Repository\PlaceRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'list_places', description: 'Read the places the user goes to — home (« Maison ») first, then by name — as {places: [...]}. A place has id, name, address, latitude and longitude (null until the address is located), parkingAddress, parkingLatitude and parkingLongitude (where to leave the car, null when none), and isHome. Call it before save_place to change a place, or to know where « la maison » or « le dentiste » is.')]
final class ListPlacesTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly PlaceRepository $repository,
    ) {
    }

    public function __invoke(): string
    {
        try {
            $user = $this->userContext->requireUser();

            return json_encode(['places' => array_map(PlaceJson::of(...), $this->repository->findByUser($user))], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
