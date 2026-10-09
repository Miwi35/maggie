<?php

namespace Maggie\Finance\Tests\Service;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Service\TransactionMatcher;
use PHPUnit\Framework\TestCase;

/**
 * The one engine the rules and the recurring operations recognise a
 * transaction with: counterparty first, then label, direction and amount.
 */
class TransactionMatcherTest extends TestCase
{
    private function transaction(string $label, int $amountCents, ?string $counterparty = null): Transaction
    {
        $user = (new User())->setEmail('matcher@example.com')->setGoogleId('google-matcher')->setName('Matcher');
        $account = (new Account())->setName('Courant')->setType(AccountType::Checking)->setUser($user);

        return (new Transaction())
            ->setUser($user)
            ->setAccount($account)
            ->setLabel($label)
            ->setAmountCents($amountCents)
            ->setCounterpartyName($counterparty)
            ->setBookedAt(new \DateTimeImmutable('2027-01-12'));
    }

    private function series(int $amountCents = -1349): RecurringOperation
    {
        return (new RecurringOperation())
            ->setLabel('Abonnement Flixo')
            ->setReferenceAmountCents($amountCents)
            ->setAnchorOn(new \DateTimeImmutable('2027-01-12'));
    }

    public function testTheCounterpartyRecognisesAnOperationWhateverTheLabelSays(): void
    {
        $matcher = new TransactionMatcher();
        $criteria = $this->series()->setCounterpartyName('FLIXO SAS')->matchCriteria();

        self::assertTrue($matcher->matches($criteria, $this->transaction('PRLV SEPA 8812 REF X', -1349, 'Flixo S.A.S.')));
        self::assertFalse($matcher->matches($criteria, $this->transaction('PRLV SEPA FLIXO SAS', -1349, 'Videoclub')), 'another counterparty is another payee, even with the name in the label');
    }

    public function testTheDirectionOfTheReferenceAmountIsKept(): void
    {
        $matcher = new TransactionMatcher();
        $criteria = $this->series()->setCounterpartyName('Flixo')->matchCriteria();

        self::assertFalse($matcher->matches($criteria, $this->transaction('AVOIR FLIXO', 1349, 'Flixo')), 'a refund is not the subscription');
    }

    public function testTheLabelPatternStandsInWhenTheTransactionHasNoCounterparty(): void
    {
        $matcher = new TransactionMatcher();
        $criteria = $this->series()->setCounterpartyName('Flixo')->setLabelPattern('flixo')->matchCriteria();

        self::assertTrue($matcher->matches($criteria, $this->transaction('CB FLIXO 12/01', -1349)));
        self::assertFalse($matcher->matches($criteria, $this->transaction('CB VIDEOCLUB 12/01', -1349)));
    }

    public function testWithoutAPatternTheCounterpartyIsReadInTheFoldedLabel(): void
    {
        $matcher = new TransactionMatcher();
        $criteria = $this->series()->setCounterpartyName('Flixo')->matchCriteria();

        self::assertTrue($matcher->matches($criteria, $this->transaction('CB F.L.I.X.O 12/01', -1349)));
        self::assertFalse($matcher->matches($criteria, $this->transaction('CB VIDEOCLUB 12/01', -1349)));
    }

    public function testTheFoldedLabelIsReadWordByWord(): void
    {
        $matcher = new TransactionMatcher();
        $criteria = $this->series()->setCounterpartyName('SFR')->matchCriteria();

        self::assertTrue($matcher->matches($criteria, $this->transaction('PRLV SFR 0612', -1349)));
        self::assertFalse($matcher->matches($criteria, $this->transaction('VIR TRANSFR 0612', -1349)), 'a three-letter payee is not found inside another word');
    }

    public function testNothingToRecogniseByRecognisesNothing(): void
    {
        self::assertFalse((new TransactionMatcher())->matches($this->series()->matchCriteria(), $this->transaction('CB FLIXO', -1349, 'Flixo')));
    }

    public function testARuleStillMatchesOnItsLabelAndAmountBoundsEvenWhenTheTransactionHasACounterparty(): void
    {
        $matcher = new TransactionMatcher();
        $rule = (new CategorizationRule())
            ->setLabelPattern('CARREFOUR')
            ->setMatchType(MatchType::StartsWith)
            ->setDirection(AmountDirection::Debit)
            ->setMinAmountCents(1000)
            ->setMaxAmountCents(5000);

        self::assertTrue($matcher->matches($rule->matchCriteria(), $this->transaction('CARREFOUR MARKET 4412', -4599, 'Carrefour Market')));
        self::assertFalse($matcher->matches($rule->matchCriteria(), $this->transaction('CARREFOUR MARKET 4412', -5001, 'Carrefour Market')));
        self::assertFalse($matcher->matches($rule->matchCriteria(), $this->transaction('CARREFOUR MARKET 4412', 4599, 'Carrefour Market')));
        self::assertFalse($matcher->matches($rule->matchCriteria(), $this->transaction('MARCHE CARREFOUR', -4599)));
    }
}
