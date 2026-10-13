<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Category;
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Message\UpdateCategoryCommand;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Service\CategoryTreeGuard;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\UseCase\UpdateCategory;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateCategoryHandler
{
    public function __construct(
        private readonly UpdateCategory $updateCategory,
        private readonly OwnedReferenceResolver $references,
        private readonly CategoryRepository $categoryRepository,
        private readonly CategoryTreeGuard $tree,
    ) {
    }

    public function __invoke(UpdateCategoryCommand $command): Category
    {
        $category = $this->categoryRepository->findOneBy(['id' => $command->categoryId, 'user' => $command->userId])
            ?? throw new \DomainException("Category not found: {$command->categoryId}");

        if (null !== $command->name) {
            $category->setName($command->name);
        }
        if (null !== $command->obligation) {
            $category->setObligation(ObligationFlag::from($command->obligation));
        }
        if (null !== $command->passiveIncome) {
            $category->setPassiveIncome($command->passiveIncome);
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
                $parent = $this->references->category($command->parentId, $category->getUser(), 'Parent category');

                $this->tree->assertCanSitUnder($category, $parent);

                $category->setParent($parent);
            }
        } elseif ($command->clears('parentId')) {
            $category->setParent(null);
        }

        // Checked after every field is in: moving a rente to a spending
        // obligation is the same contradiction as flagging a spending one.
        if ($category->declaresARenteWithoutIncome()) {
            throw new \DomainException(Category::RENTE_WITHOUT_INCOME);
        }

        return $this->updateCategory->execute($category);
    }
}
