<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\CategoryRepository;

/**
 * Resolves the entities a command points to by id, only when they belong to
 * the given owner. Someone else's object is reported exactly like a missing
 * one, so the refusal does not reveal that it exists.
 */
class OwnedReferenceResolver
{
    public function __construct(
        private readonly AccountRepository $accountRepository,
        private readonly CategoryRepository $categoryRepository,
    ) {
    }

    public function account(string $id, User $owner): Account
    {
        $account = $this->accountRepository->find($id);

        if (!$account instanceof Account || !$account->getUser()->getId()->equals($owner->getId())) {
            throw new \DomainException("Account not found: {$id}");
        }

        return $account;
    }

    public function category(string $id, User $owner, string $label = 'Category'): Category
    {
        $category = $this->categoryRepository->find($id);

        if (!$category instanceof Category || !$category->getUser()->getId()->equals($owner->getId())) {
            throw new \DomainException("{$label} not found: {$id}");
        }

        return $category;
    }
}
