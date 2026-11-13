<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Entity\Account;

class LinkAccountToBank
{
    public function __construct(
        private readonly MergeDuplicateAccounts $mergeDuplicateAccounts,
    ) {
    }

    /**
     * @return array{kept: string, moved: int, dropped: int, linked: bool}
     */
    public function execute(Account $kept, Account $bankAccount, bool $confirm): array
    {
        if ((string) $kept->getId() === (string) $bankAccount->getId()) {
            throw new \DomainException($this->refusal('Both ids name the same account.', $kept, $bankAccount));
        }
        if (!$kept->getUser()->getId()->equals($bankAccount->getUser()->getId())) {
            throw new \DomainException($this->refusal('Both accounts must belong to the user.', $kept, $bankAccount));
        }
        if (null !== $kept->getBankConnection()) {
            throw new \DomainException($this->refusal('The account to keep is already linked to a bank.', $kept, $bankAccount));
        }
        if (null === $bankAccount->getBankConnection()) {
            throw new \DomainException($this->refusal('The account to absorb is not linked to a bank.', $kept, $bankAccount));
        }
        if ($kept->getCurrency() !== $bankAccount->getCurrency()) {
            throw new \DomainException($this->refusal('The two accounts do not have the same currency.', $kept, $bankAccount));
        }

        $report = $this->mergeDuplicateAccounts->fold($kept, $bankAccount, !$confirm);

        return [
            'kept' => (string) $kept->getId(),
            'moved' => $report['moved'],
            'dropped' => $report['dropped'],
            'linked' => $confirm,
        ];
    }

    private function refusal(string $reason, Account $kept, Account $bankAccount): string
    {
        return $reason.' '.json_encode([
            'accountId' => $this->describe($kept),
            'bankAccountId' => $this->describe($bankAccount),
        ], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function describe(Account $account): array
    {
        return [
            'id' => (string) $account->getId(),
            'name' => $account->getName(),
            'type' => $account->getType()->value,
            'currency' => $account->getCurrency(),
            'linkedToBank' => null !== $account->getBankConnection(),
        ];
    }
}
