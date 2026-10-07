<?php

namespace Maggie\Finance\Tests\Entity;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\AccountType;
use PHPUnit\Framework\TestCase;

/**
 * Who a movement was paid to or by, as a payee kept apart from the label.
 *
 * The key is what two spellings of one payee group under: one function
 * computes it, so every writer lands on the same value.
 */
class TransactionCounterpartyTest extends TestCase
{
    private function transaction(): Transaction
    {
        $user = (new User())->setEmail('payee@example.com')->setGoogleId('google-payee')->setName('Payee');
        $account = (new Account())->setName('Courant')->setType(AccountType::Checking)->setUser($user);

        return (new Transaction())
            ->setUser($user)
            ->setAccount($account)
            ->setLabel('PRLV SEPA')
            ->setBookedAt(new \DateTimeImmutable('2026-09-12'));
    }

    public function testTheKeyFoldsCaseAccentsAndPunctuation(): void
    {
        $transaction = $this->transaction()->setCounterpartyName('  Crédit   Agricole, S.A. ');

        self::assertSame('Crédit Agricole, S.A.', $transaction->getCounterpartyName());
        self::assertSame('credit agricole sa', $transaction->getCounterpartyKey());
    }

    public function testANameWithNothingToFoldOnKeepsItsNameButHasNoKey(): void
    {
        $transaction = $this->transaction()->setCounterpartyName('***');

        self::assertSame('***', $transaction->getCounterpartyName());
        self::assertNull($transaction->getCounterpartyKey());
    }

    public function testNullOrBlankClearsBoth(): void
    {
        $transaction = $this->transaction()->setCounterpartyName('NETFLIX');

        $transaction->setCounterpartyName('   ');

        self::assertNull($transaction->getCounterpartyName());
        self::assertNull($transaction->getCounterpartyKey());
    }

    public function testBothChannelsCarryTheCounterparty(): void
    {
        $transaction = $this->transaction()->setCounterpartyName('NETFLIX INTERNATIONAL');

        $payload = $transaction->toMercurePayload();
        $document = $transaction->toSearchDocument();

        self::assertSame('NETFLIX INTERNATIONAL', $payload['counterpartyName']);
        self::assertSame('netflix international', $payload['counterpartyKey']);
        self::assertSame('NETFLIX INTERNATIONAL', $document['counterpartyName']);
        self::assertSame('netflix international', $document['counterpartyKey']);
    }
}
