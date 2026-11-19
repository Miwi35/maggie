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

#[McpTool(name: 'manage_categories', description: 'List, create, update or delete budget categories. A tree of at most three levels (parentId; a level-3 category has no children, none moves under its own descendants or past level 3). Read with depth (1-3) and path (names joined by « › »). obligation (deprecated, only the default qualification of a new transaction line): mandatory, optional, saving, investment, debt or income. passiveIncome flags an income category whose money comes in without work (rent, dividends, interest, royalties): the independence counter counts it; refused on any other category. On update only provided fields change; list parentId, color or icon in clear to empty them.')]
class ManageCategoriesTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly CategoryRepository $categoryRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param list<string>|null $clear */
    public function __invoke(
        string $action,
        ?string $categoryId = null,
        ?string $name = null,
        ?string $obligation = null,
        ?bool $passiveIncome = null,
        ?string $parentId = null,
        ?string $color = null,
        ?string $icon = null,
        ?array $clear = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($name, $obligation, $passiveIncome, $parentId, $color, $icon),
                'update' => $this->update($categoryId, $name, $obligation, $passiveIncome, $parentId, $color, $icon, $clear),
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

    private function create(?string $name, ?string $obligation, ?bool $passiveIncome, ?string $parentId, ?string $color, ?string $icon): string
    {
        if (null === $name) {
            return json_encode(['error' => 'Name is required for create.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $envelope = $this->bus->dispatch(new CreateCategoryCommand(
            userId: (string) $user->getId(),
            name: $name,
            obligation: $obligation ?? 'optional',
            passiveIncome: $passiveIncome ?? false,
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

    /** @param list<string>|null $clear */
    private function update(?string $categoryId, ?string $name, ?string $obligation, ?bool $passiveIncome, ?string $parentId, ?string $color, ?string $icon, ?array $clear): string
    {
        if (null === $categoryId) {
            return json_encode(['error' => 'categoryId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new UpdateCategoryCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            categoryId: $categoryId,
            name: $name,
            obligation: $obligation,
            passiveIncome: $passiveIncome,
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
        if (null === $categoryId) {
            return json_encode(['error' => 'categoryId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteCategoryCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            categoryId: $categoryId,
        ));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function serialize(Category $category): array
    {
        return [
            'id' => (string) $category->getId(),
            'name' => $category->getName(),
            'obligation' => $category->getObligation()->value,
            'passiveIncome' => $category->isPassiveIncome(),
            'parentId' => null !== $category->getParent() ? (string) $category->getParent()->getId() : null,
            'depth' => $category->getDepth(),
            'path' => $category->getPath(),
            'color' => $category->getColor(),
            'icon' => $category->getIcon(),
        ];
    }
}
