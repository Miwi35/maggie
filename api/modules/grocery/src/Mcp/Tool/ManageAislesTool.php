<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Exception\InvalidAisleException;
use Maggie\Grocery\Message\CreateAisleCommand;
use Maggie\Grocery\Message\DeleteAisleCommand;
use Maggie\Grocery\Message\ReorderAislesCommand;
use Maggie\Grocery\Message\UpdateAisleCommand;
use Maggie\Grocery\Repository\AisleRepository;
use Maggie\Grocery\Service\OwnedAisles;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_aisles', description: 'List, create, update (rename and/or move), reorder or delete the aisles of a store. Aisles are the shelves of one store in the order the user walks through it (position 1 = first); the grocery list shows each store\'s items aisle by aisle in that order, items without an aisle last. list/create/reorder need storeId; update/delete need aisleId. create puts the aisle last unless position says where. reorder takes aisleIds: every aisle of the store, first one first. Deleting an aisle leaves its products without an aisle in that store. To put a product in an aisle, use assign_product_store.')]
class ManageAislesTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly AisleRepository $aisleRepository,
        private readonly OwnedAisles $ownedAisles,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param list<string>|null $aisleIds */
    public function __invoke(
        string $action,
        ?string $storeId = null,
        ?string $aisleId = null,
        ?string $name = null,
        ?int $position = null,
        ?array $aisleIds = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list($storeId),
                'create' => $this->create($storeId, $name, $position),
                'update' => $this->update($aisleId, $name, $position),
                'reorder' => $this->reorder($storeId, $aisleIds),
                'delete' => $this->delete($aisleId),
                default => self::error("Unknown action: {$action}. Use list, create, update, reorder, or delete."),
            };
        } catch (MissingMcpUserException|InvalidAisleException $e) {
            return self::error($e->getMessage());
        } catch (HandlerFailedException $e) {
            return self::error(($e->getPrevious() ?? $e)->getMessage());
        }
    }

    private function list(?string $storeId): string
    {
        if (null === $storeId) {
            return self::error('storeId is required for list.');
        }

        $store = $this->ownedAisles->store($this->userContext->requireUser(), $storeId);

        return json_encode([
            'store' => ['id' => (string) $store->getId(), 'name' => $store->getName()],
            'aisles' => array_map(self::describe(...), $this->aisleRepository->findByStore($store)),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $storeId, ?string $name, ?int $position): string
    {
        if (null === $storeId || null === $name) {
            return self::error('storeId and name are required for create.');
        }

        $user = $this->userContext->requireUser();
        $envelope = $this->bus->dispatch(new CreateAisleCommand(
            userId: (string) $user->getId(),
            storeId: $storeId,
            name: $name,
            position: $position,
        ));

        return self::success($envelope->last(HandledStamp::class)?->getResult());
    }

    private function update(?string $aisleId, ?string $name, ?int $position): string
    {
        if (null === $aisleId) {
            return self::error('aisleId is required for update.');
        }
        if (null === $name && null === $position) {
            return self::error('Give a new name, a new position, or both.');
        }

        $user = $this->userContext->requireUser();
        $envelope = $this->bus->dispatch(new UpdateAisleCommand(
            userId: (string) $user->getId(),
            aisleId: $aisleId,
            name: $name,
            position: $position,
        ));

        return self::success($envelope->last(HandledStamp::class)?->getResult());
    }

    /** @param list<string>|null $aisleIds */
    private function reorder(?string $storeId, ?array $aisleIds): string
    {
        if (null === $storeId || null === $aisleIds) {
            return self::error('storeId and aisleIds are required for reorder.');
        }

        $user = $this->userContext->requireUser();
        $envelope = $this->bus->dispatch(new ReorderAislesCommand(
            userId: (string) $user->getId(),
            storeId: $storeId,
            aisleIds: array_map(strval(...), $aisleIds),
        ));

        /** @var Aisle[] $aisles */
        $aisles = $envelope->last(HandledStamp::class)?->getResult() ?? [];

        return json_encode(['success' => true, 'aisles' => array_map(self::describe(...), $aisles)], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $aisleId): string
    {
        if (null === $aisleId) {
            return self::error('aisleId is required for delete.');
        }

        $user = $this->userContext->requireUser();
        $this->bus->dispatch(new DeleteAisleCommand(userId: (string) $user->getId(), aisleId: $aisleId));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /** @return array{id: string, name: string, position: int, storeId: string} */
    private static function describe(Aisle $aisle): array
    {
        return $aisle->toSummary() + ['storeId' => (string) $aisle->getStore()->getId()];
    }

    private static function success(mixed $aisle): string
    {
        \assert($aisle instanceof Aisle);

        return json_encode(['success' => true, 'aisle' => self::describe($aisle)], JSON_THROW_ON_ERROR);
    }

    private static function error(string $message): string
    {
        return json_encode(['error' => $message], JSON_THROW_ON_ERROR);
    }
}
