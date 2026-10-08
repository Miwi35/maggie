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
 * people connect a bank they have been tracking by hand for months. So is an
 * account a previous consent brought: the provider names it with a new uid in
 * every session, so it is recognised by its identification instead (MAG-351).
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
        $claimed = [];

        foreach ($this->readAccounts($session) as $remote) {
            $externalId = $this->readExternalId($remote);
            if (null === $externalId) {
                continue;
            }

            $externalKey = EnableBankingClient::accountKey($remote);
            $account = $this->matchExistingAccount($connection, $remote, $externalId, $externalKey, $claimed);

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
            if (null !== $externalKey) {
                $account->setExternalKey($externalKey);
            }
            $account->setClosedAt(null);
            $account->setBankConnection($connection);
            $claimed[(string) $account->getId()] = true;
            $touched[] = $account;
        }

        // What the bank no longer lists is closed, not deleted: its movements
        // stay in the history, and it reopens if the bank lists it again. A
        // session that lists nothing readable closes nothing: that is an
        // answer gone wrong, not every account gone.
        foreach ([] === $claimed ? [] : $this->accountRepository->findByUser($connection->getUser()) as $account) {
            if (isset($claimed[(string) $account->getId()]) || $account->isClosed()) {
                continue;
            }
            if (!$account->getBankConnection()?->getId()?->equals($connection->getId())) {
                continue;
            }
            $account->setClosedAt(new \DateTimeImmutable());
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
     * The account this remote one already is, in order of certainty: the same
     * identification at the bank, the same uid, then — for an account linked
     * before identifications were kept — the only one of this connection with
     * the same name and currency, and last an account typed in by hand.
     *
     * @param array<string, mixed> $remote
     * @param array<string, true>  $claimed accounts already matched in this session
     */
    private function matchExistingAccount(BankConnection $connection, array $remote, string $externalId, ?string $externalKey, array $claimed): ?Account
    {
        $accounts = array_values(array_filter(
            $this->accountRepository->findByUser($connection->getUser()),
            static fn (Account $account) => !isset($claimed[(string) $account->getId()]),
        ));

        if (null !== $externalKey) {
            foreach ($accounts as $account) {
                if ($account->getExternalKey() === $externalKey) {
                    return $account;
                }
            }
        }

        foreach ($accounts as $account) {
            if ($account->getExternalAccountId() === $externalId) {
                return $account;
            }
        }

        $name = mb_strtolower($this->readName($remote, $connection->getBankName()));
        $currency = $this->readCurrency($remote);

        $legacy = array_values(array_filter(
            $accounts,
            static fn (Account $account) => null === $account->getExternalKey()
                && null !== $account->getExternalAccountId()
                && true === $account->getBankConnection()?->getId()?->equals($connection->getId())
                && mb_strtolower($account->getName()) === $name
                && $account->getCurrency() === $currency,
        ));

        // Two of them is the duplication itself, or two real accounts of the
        // same name: guessing would be worse than one more copy to merge.
        if (1 === \count($legacy)) {
            return $legacy[0];
        }

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
