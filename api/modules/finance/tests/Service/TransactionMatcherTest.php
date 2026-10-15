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
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Service\MatchCriteria;
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

    public function testADirectionAndAnAmountRangeRecogniseATransactionWhateverItsLabelAndCounterparty(): void
    {
        $matcher = new TransactionMatcher();
        $criteria = new MatchCriteria(direction: AmountDirection::Credit, minAmountCents: 427500, maxAmountCents: 522500);

        self::assertTrue($criteria->isRecognisable());
        self::assertTrue($matcher->matches($criteria, $this->transaction('VIR SEPA XYZ', 460000, 'Employeur Un')));
        self::assertTrue($matcher->matches($criteria, $this->transaction('SALAIRE OCTOBRE', 475000, 'Employeur Deux')));
        self::assertFalse($matcher->matches($criteria, $this->transaction('VIR SEPA XYZ', 300000)), 'out of the range');
        self::assertFalse($matcher->matches($criteria, $this->transaction('PRLV 4750', -475000)), 'the other direction');
    }

    public function testAnAmountAloneRecognisesNothingWithoutADirection(): void
    {
        $matcher = new TransactionMatcher();
        $criteria = new MatchCriteria(minAmountCents: 427500, maxAmountCents: 522500);

        self::assertFalse($criteria->isRecognisable());
        self::assertFalse($matcher->matches($criteria, $this->transaction('VIR SEPA XYZ', 475000)));
        self::assertFalse($matcher->matches($criteria, $this->transaction('PRLV', -475000)));
    }

    public function testACriterionWithNothingInItIsNeverRecognisable(): void
    {
        $matcher = new TransactionMatcher();

        foreach ([new MatchCriteria(), new MatchCriteria(labelPattern: '', counterpartyKey: ''), new MatchCriteria(direction: AmountDirection::Credit)] as $criteria) {
            self::assertFalse($criteria->isRecognisable());
            self::assertFalse($matcher->matches($criteria, $this->transaction('ANYTHING', 100)));
        }
    }

    public function testAnInternalTransferIsNotRecognisedOnItsAmountAlone(): void
    {
        $matcher = new TransactionMatcher();
        $criteria = new MatchCriteria(direction: AmountDirection::Credit, minAmountCents: 427500, maxAmountCents: 522500);
        $transfer = $this->transaction('VIR INTERNE', 475000)->setTransferKind(TransferKind::Internal);

        self::assertFalse($matcher->matches($criteria, $transfer));
        self::assertTrue($matcher->matches(new MatchCriteria(labelPattern: 'vir interne'), $transfer), 'a named criterion is unchanged');
    }
}
