<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Enum\BankConnectionStatus;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\BankConnectionRepository;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Finishes the journey the bank is answering about: turns the one-time code
 * into a session, and brings the accounts it grants access to.
 *
 * An account the user already typed in is matched rather than duplicated —
 * people connect a bank they have been tracking by hand for months.
 */
class CompleteBankAuthorization
{
    public function __construct(
        private readonly EnableBankingClient $client,
        private readonly BankConnectionRepository $connectionRepository,
        private readonly AccountRepository $accountRepository,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** @return array{connection: BankConnection, linked: int, created: int} */
    public function execute(string $state, string $code): array
    {
        $connection = $this->connectionRepository->findOneByState($state)
            ?? throw new \DomainException('This authorization does not match any pending connection.');

        // The endpoint is public, so a state must be good exactly once:
        // replaying one that already succeeded must not re-open anything.
        if (BankConnectionStatus::Pending !== $connection->getStatus()) {
            throw new \DomainException('This authorization has already been used.');
        }

        $session = $this->client->createSession($code);

        $sessionId = $session['session_id'] ?? null;
        if (!\is_string($sessionId) || '' === $sessionId) {
            throw new \RuntimeException('The provider returned no session for this authorization.');
        }

        $connection->activate($sessionId, $this->readConsentExpiry($session));

        $linked = 0;
        $created = 0;
        $touched = [];

        foreach ($this->readAccounts($session) as $remote) {
            $externalId = $this->readExternalId($remote);
            if (null === $externalId) {
                continue;
            }

            $account = $this->matchExistingAccount($connection, $remote, $externalId);

            if (null === $account) {
                $account = new Account();
                $account->setUser($connection->getUser());
                $account->setName($this->readName($remote, $connection->getBankName()));
                $account->setBank($connection->getBankName());
                $account->setType(AccountType::Checking);
                $account->setCurrency($this->readCurrency($remote));
                $this->em->persist($account);
                ++$created;
            } else {
                ++$linked;
            }

            $account->setExternalAccountId($externalId);
            $account->setBankConnection($connection);
            $touched[] = $account;
        }

        $this->em->flush();

        // Written straight to the database, so nothing on the bus indexed
        // them: an account absent from Elasticsearch is absent from the list.
        foreach ($touched as $account) {
            $this->bus->dispatch(new IndexDocumentCommand(
                entityClass: Account::class,
                entityId: (string) $account->getId(),
            ));
        }

        return ['connection' => $connection, 'linked' => $linked, 'created' => $created];
    }

    /**
     * An account already tracked by hand: same external id if it was linked
     * before, otherwise the same name at the same bank.
     *
     * @param array<string, mixed> $remote
     */
    private function matchExistingAccount(BankConnection $connection, array $remote, string $externalId): ?Account
    {
        $accounts = $this->accountRepository->findByUser($connection->getUser());

        foreach ($accounts as $account) {
            if ($account->getExternalAccountId() === $externalId) {
                return $account;
            }
        }

        $name = mb_strtolower($this->readName($remote, $connection->getBankName()));

        foreach ($accounts as $account) {
            if (null !== $account->getExternalAccountId()) {
                continue;
            }
            if (mb_strtolower($account->getName()) === $name
                || mb_strtolower((string) $account->getBank()) === mb_strtolower($connection->getBankName())
            ) {
                return $account;
            }
        }

        return null;
    }

    /**
     * @param array<string, mixed> $session
     *
     * @return array<int, array<string, mixed>>
     */
    private function readAccounts(array $session): array
    {
        $accounts = $session['accounts'] ?? [];

        return array_values(array_filter($accounts, 'is_array'));
    }

    /** @param array<string, mixed> $session */
    private function readConsentExpiry(array $session): ?\DateTimeImmutable
    {
        $validUntil = $session['access']['valid_until'] ?? null;

        if (!\is_string($validUntil)) {
            return null;
        }

        try {
            return new \DateTimeImmutable($validUntil);
        } catch (\Exception) {
            return null;
        }
    }

    /** @param array<string, mixed> $remote */
    private function readExternalId(array $remote): ?string
    {
        foreach (['uid', 'resource_id', 'id'] as $key) {
            if (isset($remote[$key]) && \is_string($remote[$key]) && '' !== $remote[$key]) {
                return $remote[$key];
            }
        }

        return null;
    }

    /** @param array<string, mixed> $remote */
    private function readName(array $remote, string $fallback): string
    {
        foreach (['name', 'product', 'details'] as $key) {
            if (isset($remote[$key]) && \is_string($remote[$key]) && '' !== trim($remote[$key])) {
                return trim($remote[$key]);
            }
        }

        $iban = $remote['account_id']['iban'] ?? null;
        if (\is_string($iban) && '' !== $iban) {
            // The last four digits are enough to tell two accounts apart.
            return sprintf('%s ••%s', $fallback, substr($iban, -4));
        }

        return $fallback;
    }

    /** @param array<string, mixed> $remote */
    private function readCurrency(array $remote): string
    {
        $currency = $remote['currency'] ?? null;

        return \is_string($currency) && preg_match('/^[A-Z]{3}$/', $currency) ? $currency : 'EUR';
    }
}
