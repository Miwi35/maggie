<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Projection\WorkCollector;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Repository\CategoryRepository;

class UpdateCategory
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly CategoryRepository $categoryRepository,
        private readonly WorkCollector $projection,
    ) {
    }

    public function execute(Category $category): Category
    {
        // Read before the flush: it is gone after, and the REST processor has
        // already applied the body to the entity by the time the handler runs.
        $uow = $this->em->getUnitOfWork();
        $uow->computeChangeSets();
        $changes = $uow->getEntityChangeSet($category);
        $pathChanged = isset($changes['name']) || isset($changes['parent']);

        $this->em->flush();

        // Their path runs through this category: the screens and the index must hear of the new one.
        if ($pathChanged) {
            foreach ($this->categoryRepository->findDescendants($category) as $descendant) {
                $this->projection->touched($descendant, ['name', 'parent']);
            }
        }

        return $category;
    }
}
