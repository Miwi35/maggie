<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Category\StandardCategories;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Repository\CategoryRepository;

/**
 * Gives a standard key to the category the owner made by hand before the key
 * existed — a top-level « Salaire » typed long ago is the salary.
 *
 * Idempotent: a key one of the user's categories already holds is left where
 * it is, and nothing else is touched — name, colour, obligation stay as they
 * are. Only a top-level category is taken, matched on its name the way the
 * standard set compares them.
 *
 * It flushes and projects nothing by hand: through the bus
 * (`TagStandardCategoriesCommand`) the projection publishes what changed,
 * and `InstallStandardCategories` indexes what it returns with its own.
 */
class TagStandardCategories
{
    public function __construct(
        private readonly CategoryRepository $categoryRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    /** @return array<string, Category> key => the category that received it */
    public function execute(User $user): array
    {
        $roots = $this->categoryRepository->findRootsByUser($user);

        $held = [];
        foreach ($this->categoryRepository->findByUser($user) as $category) {
            if (null !== $category->getStandardKey()) {
                $held[$category->getStandardKey()] = true;
            }
        }

        $tagged = [];
        foreach (StandardCategories::keyed() as $key => $name) {
            if (isset($held[$key])) {
                continue;
            }

            foreach ($roots as $category) {
                if (null === $category->getStandardKey() && self::sameName($category->getName(), $name)) {
                    $category->setStandardKey($key);
                    $tagged[$key] = $category;
                    break;
                }
            }
        }

        if ([] !== $tagged) {
            $this->em->flush();
        }

        return $tagged;
    }

    /** Names are compared the way a person reads them, not byte for byte. */
    public static function sameName(string $a, string $b): bool
    {
        return mb_strtolower(trim($a)) === mb_strtolower(trim($b));
    }
}
