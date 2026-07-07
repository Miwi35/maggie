<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\DeleteCategoryCommand;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\UseCase\DeleteCategory;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteCategoryHandler
{
    public function __construct(
        private readonly DeleteCategory $deleteCategory,
        private readonly CategoryRepository $categoryRepository,
    ) {
    }

    public function __invoke(DeleteCategoryCommand $command): void
    {
        $category = $this->categoryRepository->find($command->categoryId)
            ?? throw new \DomainException("Category not found: {$command->categoryId}");

        $this->deleteCategory->execute($category);
    }
}
