<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Category;
use Maggie\Finance\Repository\CategoryRepository;

/** Keeps the category tree to {@see Category::MAX_DEPTH} levels and free of cycles. */
class CategoryTreeGuard
{
    public function __construct(
        private readonly CategoryRepository $categoryRepository,
    ) {
    }

    /**
     * @throws \DomainException when `$category` (with everything below it) cannot sit under `$parent`
     */
    public function assertCanSitUnder(Category $category, Category $parent): void
    {
        for ($ancestor = $parent; null !== $ancestor; $ancestor = $ancestor->getParent()) {
            if ($ancestor->getId()->equals($category->getId())) {
                throw new \DomainException($ancestor === $parent ? 'A category cannot be its own parent.' : 'A category cannot be moved under one of its own sub-categories.');
            }
        }

        $below = 0;
        foreach ($this->categoryRepository->findDescendants($category) as $descendant) {
            $below = max($below, $descendant->getDepth() - $category->getDepth());
        }

        if ($parent->getDepth() + 1 + $below > Category::MAX_DEPTH) {
            throw new \DomainException(\sprintf('Categories support at most %d levels: %s', Category::MAX_DEPTH, 0 === $below ? 'a level-'.Category::MAX_DEPTH.' category cannot have children.' : 'moving this category there would push its sub-categories past level '.Category::MAX_DEPTH.'.'));
        }
    }
}
