<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Message\CreateCategoryCommand;
use Maggie\Finance\Message\DeleteCategoryCommand;
use Maggie\Finance\Message\UpdateCategoryCommand;
use Maggie\Finance\Repository\CategoryRepository;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_categories', description: 'List, create, update, or delete budget categories. Categories form a two-level tree (a category may have a parentId, sub-categories cannot have children). Each category has an obligation flag: mandatory, optional, saving, or investment. On update, only provided fields change; to empty an optional field (make it a top-level category, drop its color or icon), list parentId, color or icon in clear.')]
class ManageCategoriesTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly CategoryRepository $categoryRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?string $categoryId = null,
        ?string $name = null,
        ?string $obligation = null,
        ?string $parentId = null,
        ?string $color = null,
        ?string $icon = null,
        ?array $clear = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($name, $obligation, $parentId, $color, $icon),
                'update' => $this->update($categoryId, $name, $obligation, $parentId, $color, $icon, $clear),
                'delete' => $this->delete($categoryId),
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

        $categories = $this->categoryRepository->findByUser($user);

        return json_encode([
            'categories' => array_map(fn (Category $c) => $this->serialize($c), $categories),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $name, ?string $obligation, ?string $parentId, ?string $color, ?string $icon): string
    {
        if ($name === null) {
            return json_encode(['error' => 'Name is required for create.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $envelope = $this->bus->dispatch(new CreateCategoryCommand(
            userId: (string) $user->getId(),
            name: $name,
            obligation: $obligation ?? 'optional',
            parentId: $parentId,
            color: $color,
            icon: $icon,
        ));

        /** @var Category $category */
        $category = $envelope->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'category' => $this->serialize($category),
        ], JSON_THROW_ON_ERROR);
    }

    private function update(?string $categoryId, ?string $name, ?string $obligation, ?string $parentId, ?string $color, ?string $icon, ?array $clear): string
    {
        if ($categoryId === null) {
            return json_encode(['error' => 'categoryId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new UpdateCategoryCommand(
            categoryId: $categoryId,
            name: $name,
            obligation: $obligation,
            parentId: $parentId,
            color: $color,
            icon: $icon,
            clearFields: array_values(array_intersect($clear ?? [], ['parentId', 'color', 'icon'])),
        ));

        /** @var Category $category */
        $category = $envelope->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'category' => $this->serialize($category),
        ], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $categoryId): string
    {
        if ($categoryId === null) {
            return json_encode(['error' => 'categoryId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteCategoryCommand(categoryId: $categoryId));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function serialize(Category $category): array
    {
        return [
            'id' => (string) $category->getId(),
            'name' => $category->getName(),
            'obligation' => $category->getObligation()->value,
            'parentId' => $category->getParent() !== null ? (string) $category->getParent()->getId() : null,
            'color' => $category->getColor(),
            'icon' => $category->getIcon(),
        ];
    }
}
