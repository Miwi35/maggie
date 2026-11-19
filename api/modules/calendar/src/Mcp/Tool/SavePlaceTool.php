<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Place;
use Maggie\Calendar\Exception\InvalidPlaceException;
use Maggie\Calendar\Mcp\PlaceJson;
use Maggie\Calendar\Message\SavePlaceCommand;
use Maggie\Calendar\Repository\PlaceRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'save_place', description: 'Creates a place (without id) or changes one (with id, from list_places). name and address required. latitude and longitude (degrees) go together. parkingAddress with parkingLatitude and parkingLongitude (together); empty parkingAddress removes it. isHome=true replaces the home. Omitted fields kept. Refusal changes nothing, answers {error, field}. Returns {success, place}.')]
final class SavePlaceTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly PlaceRepository $repository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(
        ?string $id = null,
        ?string $name = null,
        ?string $address = null,
        #[Schema(minimum: -90, maximum: 90)]
        ?float $latitude = null,
        #[Schema(minimum: -180, maximum: 180)]
        ?float $longitude = null,
        ?string $parkingAddress = null,
        #[Schema(minimum: -90, maximum: 90)]
        ?float $parkingLatitude = null,
        #[Schema(minimum: -180, maximum: 180)]
        ?float $parkingLongitude = null,
        ?bool $isHome = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();

            if (null !== $id && null === $this->repository->findOwned($user, $id)) {
                return self::refused(new InvalidPlaceException('id', "no place of yours has the id {$id}: list_places gives the ids."));
            }

            try {
                $envelope = $this->bus->dispatch(new SavePlaceCommand((string) $user->getId(), $id, [
                    'name' => $name,
                    'address' => $address,
                    'latitude' => $latitude,
                    'longitude' => $longitude,
                    'parkingAddress' => $parkingAddress,
                    'parkingLatitude' => $parkingLatitude,
                    'parkingLongitude' => $parkingLongitude,
                    'isHome' => $isHome,
                ]));
            } catch (HandlerFailedException $e) {
                // A refusal is Maggie's to read; anything else is ours.
                $cause = $e->getPrevious() ?? $e;
                if (!$cause instanceof InvalidPlaceException) {
                    throw $e;
                }

                return self::refused($cause);
            }

            /** @var Place $place */
            $place = $envelope->last(HandledStamp::class)?->getResult();

            return self::json(['success' => true, 'place' => PlaceJson::of($place)]);
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        }
    }

    private static function refused(InvalidPlaceException $e): string
    {
        return self::json(['error' => $e->getMessage(), 'field' => $e->field]);
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, JSON_THROW_ON_ERROR);
    }
}
