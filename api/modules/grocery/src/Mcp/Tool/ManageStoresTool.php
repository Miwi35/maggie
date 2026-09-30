<?php

declare(strict_types=1);

namespace Maggie\Grocery\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Message\CreateStoreCommand;
use Maggie\Grocery\Message\DeleteStoreCommand;
use Maggie\Grocery\Message\UpdateStoreCommand;
use Maggie\Grocery\Repository\StoreRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_stores', description: 'List, create, update, or delete stores. Stores represent shops with a visit order and a description of what they sell (used to auto-assign products). To empty an optional field on update, list its name in clear (description).')]
class ManageStoresTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly StoreRepository $storeRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?string $storeId = null,
        ?string $name = null,
        ?string $description = null,
        ?int $visitOrder = null,
        ?array $clear = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($name, $description, $visitOrder),
                'update' => $this->update($storeId, $name, $description, $visitOrder, $clear),
                'delete' => $this->delete($storeId),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, create, update, or delete."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function list(): string
    {
        $user = $this->userContext->requireUser();

        $stores = $this->storeRepository->findByUser($user);

        return json_encode([
            'stores' => array_map(fn (Store $s) => [
                'id' => (string) $s->getId(),
                'name' => $s->getName(),
                'description' => $s->getDescription(),
                'visitOrder' => $s->getVisitOrder(),
            ], $stores),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $name, ?string $description, ?int $visitOrder): string
    {
        if ($name === null) {
            return json_encode(['error' => 'Name is required for create.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $envelope = $this->bus->dispatch(new CreateStoreCommand(
            userId: (string) $user->getId(),
            name: $name,
            description: $description,
            visitOrder: $visitOrder ?? 0,
        ));

        /** @var Store $store */
        $store = $envelope->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'store' => [
                'id' => (string) $store->getId(),
                'name' => $store->getName(),
                'description' => $store->getDescription(),
                'visitOrder' => $store->getVisitOrder(),
            ],
        ], JSON_THROW_ON_ERROR);
    }

    /** @param array<mixed>|null $clear */
    private function update(?string $storeId, ?string $name, ?string $description, ?int $visitOrder, ?array $clear): string
    {
        if ($storeId === null) {
            return json_encode(['error' => 'storeId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new UpdateStoreCommand(
            storeId: $storeId,
            name: $name,
            description: $description,
            visitOrder: $visitOrder,
            clearFields: array_values(array_intersect($clear ?? [], ['description'])),
        ));

        /** @var Store $store */
        $store = $envelope->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'store' => [
                'id' => (string) $store->getId(),
                'name' => $store->getName(),
                'description' => $store->getDescription(),
                'visitOrder' => $store->getVisitOrder(),
            ],
        ], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $storeId): string
    {
        if ($storeId === null) {
            return json_encode(['error' => 'storeId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteStoreCommand(storeId: $storeId));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }
}
