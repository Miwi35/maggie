<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Repository\AccountRepository;

/**
 * Drops a bank link the user no longer wants.
 *
 * The accounts it brought are kept, only unhooked: they hold months of
 * movements the user typed or imported, and losing those because a consent was
 * dropped would be indefensible. They simply stop syncing.
 */
class ForgetBankConnection
{
    public function __construct(
        private readonly AccountRepository $accountRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(BankConnection $connection): void
    {
        foreach ($this->accountRepository->findByUser($connection->getUser()) as $account) {
            if ($account->getBankConnection()?->getId()?->equals($connection->getId()) !== true) {
                continue;
            }

            $account->setBankConnection(null);
            $account->setExternalAccountId(null);
        }

        $this->em->remove($connection);
        $this->em->flush();
    }
}
