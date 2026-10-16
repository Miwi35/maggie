<?php

namespace Maggie\Finance\Tests\Contact;

use Maggie\Finance\Contact\CounterpartyMatch;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\ContactCounterparty;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Import\MerchantExtractor;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

class CounterpartyMatchTest extends TestCase
{
    private static function transaction(string $label, ?string $counterpartyName = null, TransferKind $kind = TransferKind::None): Transaction
    {
        return (new Transaction())
            ->setLabel($label)
            ->setCounterpartyName($counterpartyName)
            ->setTransferKind($kind);
    }

    /** @return iterable<string, array{Transaction, string, bool}> */
    public static function cases(): iterable
    {
        yield 'the same label' => [self::transaction('VIR. DE TOM MARTIN'), 'vir de tom martin', true];
        yield 'the label with a reference' => [self::transaction('VIR. DE TOM MARTIN 05/09 REF 4411'), 'vir de tom martin', true];
        yield 'a longer name is someone else' => [self::transaction('VIR. DE TOM MARTINEZ'), 'vir de tom martin', false];
        yield 'an internal transfer never' => [self::transaction('VIR. DE TOM MARTIN', kind: TransferKind::Internal), 'vir de tom martin', false];
        yield 'a rejection never' => [self::transaction('VIR. DE TOM MARTIN', kind: TransferKind::Rejected), 'vir de tom martin', false];
        yield 'an advance never' => [self::transaction('VIR. DE TOM MARTIN', kind: TransferKind::Advance), 'vir de tom martin', false];
        yield 'the bank named the counterparty' => [self::transaction('VIREMENT SEPA 123', 'Tom Martin'), 'tom martin', true];
        yield 'then the label is not read' => [self::transaction('VIR. DE TOM MARTIN', 'Société X'), 'vir de tom martin', false];
    }

    #[DataProvider('cases')]
    public function testMatches(Transaction $transaction, string $matchKey, bool $expected): void
    {
        self::assertSame($expected, CounterpartyMatch::matches($transaction, $matchKey));
    }

    public function testTheLongestCounterpartyWins(): void
    {
        $tom = (new Contact())->setName('Tom');
        $other = (new Contact())->setName('Martin');
        $counterparties = [
            (new ContactCounterparty())->setContact($other)->setLabel('TOM MARTIN'),
            (new ContactCounterparty())->setContact($tom)->setLabel('VIR. DE TOM MARTIN'),
        ];

        self::assertSame($tom, CounterpartyMatch::contactFor(self::transaction('VIR. DE TOM MARTIN'), $counterparties));
        self::assertSame($other, CounterpartyMatch::contactFor(self::transaction('PRLV TOM MARTIN'), $counterparties));
        self::assertNull(CounterpartyMatch::contactFor(self::transaction('CARREFOUR'), $counterparties));
    }

    public function testTheKeyIsTheMerchantKeyWithItsSpacesCollapsed(): void
    {
        self::assertSame(MerchantExtractor::key('VIR. DE TOM MARTIN'), CounterpartyMatch::keyOf('VIR. DE TOM MARTIN'));
        self::assertSame('paypal tommartin', CounterpartyMatch::keyOf('PAYPAL * TOMMARTIN'));
    }

    public function testOnlyAnUnclaimedTransferAsksForItsContact(): void
    {
        self::assertTrue(CounterpartyMatch::asksForContact(self::transaction('VIR INST DE JULIE DURAND')));
        self::assertTrue(CounterpartyMatch::asksForContact(self::transaction('PAYPAL *TOMMARTIN')));
        self::assertTrue(CounterpartyMatch::asksForContact(self::transaction('XYZ', 'Julie Durand')));
        self::assertFalse(CounterpartyMatch::asksForContact(self::transaction('CARREFOUR MARKET')));
        self::assertFalse(CounterpartyMatch::asksForContact(self::transaction('VIR. DE TOM MARTIN', kind: TransferKind::Internal)));
        self::assertFalse(CounterpartyMatch::asksForContact(self::transaction('VIR. DE TOM MARTIN')->setContact(new Contact())));
    }

    public function testTheLabelToLinkLeavesTheReferencesOut(): void
    {
        self::assertSame('VIR. DE TOM MARTIN', CounterpartyMatch::labelFor(self::transaction('VIR. DE TOM MARTIN 05/09 REF 4411')));
        self::assertSame('PAYPAL *TOMMARTIN', CounterpartyMatch::labelFor(self::transaction('PAYPAL *TOMMARTIN')));
        self::assertSame('Julie Durand', CounterpartyMatch::labelFor(self::transaction('VIREMENT 123456789', 'Julie Durand')));
    }
}
