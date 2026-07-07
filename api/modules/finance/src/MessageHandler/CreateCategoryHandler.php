<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Message\CreateCategoryCommand;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\UseCase\CreateCategory;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateCategoryHandler
{
    public function __construct(
        private readonly CreateCategory $createCategory,
        private readonly CategoryRepository $categoryRepository,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateCategoryCommand $command): Category
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $category = new Category();
        $category->setUser($user);
        $category->setName($command->name);
        $category->setObligation(ObligationFlag::from($command->obligation));
        $category->setColor($command->color);
        $category->setIcon($command->icon);

        if ($command->parentId !== null) {
            $parent = $this->categoryRepository->find($command->parentId)
                ?? throw new \DomainException("Parent category not found: {$command->parentId}");

            if ($parent->getParent() !== null) {
                throw new \DomainException('Categories support only two levels: a sub-category cannot have children.');
            }

            $category->setParent($parent);
        }

        return $this->createCategory->execute($category);
    }
}
