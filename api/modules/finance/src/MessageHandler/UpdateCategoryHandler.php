<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Category;
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Message\UpdateCategoryCommand;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\UseCase\UpdateCategory;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateCategoryHandler
{
    public function __construct(
        private readonly UpdateCategory $updateCategory,
        private readonly CategoryRepository $categoryRepository,
    ) {
    }

    public function __invoke(UpdateCategoryCommand $command): Category
    {
        $category = $this->categoryRepository->find($command->categoryId)
            ?? throw new \DomainException("Category not found: {$command->categoryId}");

        if (null !== $command->name) {
            $category->setName($command->name);
        }
        if (null !== $command->obligation) {
            $category->setObligation(ObligationFlag::from($command->obligation));
        }
        if (null !== $command->color) {
            $category->setColor($command->color);
        } elseif ($command->clears('color')) {
            $category->setColor(null);
        }
        if (null !== $command->icon) {
            $category->setIcon($command->icon);
        } elseif ($command->clears('icon')) {
            $category->setIcon(null);
        }
        if (null !== $command->parentId) {
            if ('' === $command->parentId) {
                $category->setParent(null);
            } else {
                if ($command->parentId === $command->categoryId) {
                    throw new \DomainException('A category cannot be its own parent.');
                }

                $parent = $this->categoryRepository->find($command->parentId)
                    ?? throw new \DomainException("Parent category not found: {$command->parentId}");

                if (null !== $parent->getParent()) {
                    throw new \DomainException('Categories support only two levels: a sub-category cannot have children.');
                }

                $category->setParent($parent);
            }
        } elseif ($command->clears('parentId')) {
            $category->setParent(null);
        }

        return $this->updateCategory->execute($category);
    }
}
