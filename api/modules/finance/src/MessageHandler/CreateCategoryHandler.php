<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Message\CreateCategoryCommand;
use Maggie\Finance\Service\CategoryTreeGuard;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\UseCase\CreateCategory;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateCategoryHandler
{
    public function __construct(
        private readonly CreateCategory $createCategory,
        private readonly OwnedReferenceResolver $references,
        private readonly UserRepository $userRepository,
        private readonly CategoryTreeGuard $tree,
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
        $category->setPassiveIncome($command->passiveIncome);
        $category->setColor($command->color);
        $category->setIcon($command->icon);

        if ($category->declaresARenteWithoutIncome()) {
            throw new \DomainException(Category::RENTE_WITHOUT_INCOME);
        }

        if (null !== $command->parentId) {
            $parent = $this->references->category($command->parentId, $user, 'Parent category');

            $this->tree->assertCanSitUnder($category, $parent);

            $category->setParent($parent);
        }

        return $this->createCategory->execute($category);
    }
}
