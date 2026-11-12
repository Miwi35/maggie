<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Bank;

use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\UseCase\ReadBankStatement;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;

/** A connected account and a scripted bank, for the tests of the statement tools (MAG-802). */
trait BankStatementTestTrait
{
    private string $keyPath;

    /** @var list<string> the URLs the bank was asked for */
    private array $requested = [];

    private function makeKey(): void
    {
        $this->keyPath = tempnam(sys_get_temp_dir(), 'eb-key-');
        $key = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
        openssl_pkey_export($key, $pem);
        file_put_contents($this->keyPath, $pem);
    }

    private function dropKey(): void
    {
        @unlink($this->keyPath);
    }

    private function connectAccount(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $connection = new BankConnection();
        $connection->setUser($this->getFixture('test_user'));
        $connection->setBankName('N26');
        $connection->activate('session-1', new \DateTimeImmutable('+60 days'));
        $em->persist($connection);

        $account = $this->getFixture('checking');
        $account->setExternalAccountId('remote-1');
        $account->setBankConnection($connection);

        $em->flush();
    }

    /** @param callable(string): MockResponse $answer answers a movements request, given its URL */
    private function readerOn(callable $answer): ReadBankStatement
    {
        $this->requested = [];
        $http = new MockHttpClient(function (string $method, string $url) use ($answer) {
            $this->requested[] = $url;

            return $answer($url);
        });

        return new ReadBankStatement(
            new EnableBankingClient($http, 'app-id', $this->keyPath, 'https://api.example.test'),
            self::getContainer()->get(AccountRepository::class),
            self::getContainer()->get('logger'),
        );
    }

    /** @param list<array<string, mixed>> $transactions */
    private function pageOf(array $transactions): MockResponse
    {
        return new MockResponse(json_encode(['transactions' => $transactions], JSON_THROW_ON_ERROR));
    }

    /** @return array<string, mixed> */
    private function movement(string $date, string $amount, string $indicator, string $payee, ?string $reference = null): array
    {
        return array_filter([
            'entry_reference' => $reference,
            'booking_date' => $date,
            'transaction_amount' => ['amount' => $amount, 'currency' => 'EUR'],
            'credit_debit_indicator' => $indicator,
            'creditor' => ['name' => $payee],
            'debtor' => ['name' => $payee],
        ], static fn ($value) => null !== $value);
    }

    /** @return list<array<string, mixed>> September: two transfers from N26 and three other movements. */
    private function september(): array
    {
        return [
            $this->movement('2026-09-02', '50.00', 'CRDT', 'Virement N26 Léo', 'ref-n26-1'),
            $this->movement('2026-09-09', '30.00', 'CRDT', 'N26 Bank', 'ref-n26-2'),
            $this->movement('2026-09-11', '45.99', 'DBIT', 'CARREFOUR MARKET', 'ref-shop-1'),
            $this->movement('2026-09-15', '12.00', 'DBIT', 'BOULANGERIE', 'ref-shop-2'),
            $this->movement('2026-09-20', '3500.00', 'CRDT', 'SALAIRE', 'ref-salary'),
        ];
    }
}
