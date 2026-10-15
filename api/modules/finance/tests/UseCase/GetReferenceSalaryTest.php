<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\RecurringLinkSource;
use Maggie\Finance\Enum\ReferenceAmountSource;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\UseCase\GetReferenceSalary;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The salary the month is judged on (MAG-273): the expected amount of the
 * recurring « Salaire » income, its measured payments when the amount is
 * measured, the cushion's declared salary above both, and `unknown` rather
 * than 0 € when there is nothing.
 *
 * The world is `reference_salary.yaml`, on the real clock.
 */
class GetReferenceSalaryTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private GetReferenceSalary $referenceSalary;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->referenceSalary = self::getContainer()->get(GetReferenceSalary::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('reference_salary.yaml');
    }

    /** @return array<string, mixed> */
    private function read(string $as = 'test_user'): array
    {
        /** @var User $user */
        $user = $this->getFixture($as);

        return $this->referenceSalary->execute($user);
    }

    public function testTheExpectedSalaryIsTheMark(): void
    {
        $data = $this->read();

        self::assertSame(495000, $data['monthlyCents']);
        self::assertSame('expected', $data['source']);
        self::assertTrue($data['isConfigured']);
        self::assertNull($data['declaredMonthlyCents']);
        self::assertSame(['Salaire ACME'], array_column($data['operations'], 'label'));
        self::assertSame('expected', $data['operations'][0]['source']);
        self::assertSame(495000, $data['operations'][0]['monthlyCents']);
    }

    /** The fixture holds a yearly bonus series and a 2 000 € line in « Primes »: neither counts. */
    public function testABonusNeverMovesTheMark(): void
    {
        $this->attach('salary_op', 'checking', 200000, 0, isBonus: true);

        $data = $this->read();

        self::assertSame(495000, $data['monthlyCents']);
        self::assertNotContains('Prime annuelle ACME', array_column($data['operations'], 'label'));
        self::assertNotContains('Loyer studio', array_column($data['operations'], 'label'));
    }

    public function testTwoSalariesPaidTheSameDayDoNotMoveTheMark(): void
    {
        $sameDay = new \DateTimeImmutable('today');
        $this->attach('salary_op', 'checking', 495000, 1, bookedAt: $sameDay);
        $this->attach('salary_op', 'checking', 495000, 0, bookedAt: $sameDay);

        self::assertSame(495000, $this->read()['monthlyCents']);
    }

    public function testRenamingTheSalaryCategoryDoesNotMoveTheMark(): void
    {
        /** @var Category $salary */
        $salary = $this->getFixture('salary');
        $salary->setName('Paie');
        $this->em->flush();

        $data = $this->read();

        self::assertSame(495000, $data['monthlyCents']);
        self::assertSame('Paie', $data['operations'][0]['categoryName']);
    }

    /** A name is not a key: a hand-made « Salaire » with no key is not the salary until it is tagged. */
    public function testTheCategoryIsRecognisedByItsKeyNotItsName(): void
    {
        /** @var Category $salary */
        $salary = $this->getFixture('salary');
        $salary->setStandardKey(null);
        $this->em->flush();

        self::assertSame('unknown', $this->read()['source']);
    }

    public function testTwoEmployersAddUpAndAQuarterlySalaryCountsForAThird(): void
    {
        $this->series('test_user', 'salary', 'checking', 'Salaire trimestriel Hooli', 'HOOLI', 300000, RecurrencePeriod::Quarterly);

        $data = $this->read();

        self::assertSame(595000, $data['monthlyCents']);
        self::assertSame(['Salaire ACME', 'Salaire trimestriel Hooli'], array_column($data['operations'], 'label'));
    }

    public function testAnEndedSeriesAndOneUnderASubCategoryAreTold(): void
    {
        $ended = $this->series('test_user', 'salary', 'checking', 'Ancien employeur', 'OLDCO', 400000);
        $ended->setEndsOn(new \DateTimeImmutable('first day of this month -2 months'));

        $sub = new Category();
        $sub->setUser($this->getFixture('test_user'));
        $sub->setName('Salaire week-end');
        $sub->setObligation(ObligationFlag::Income);
        $sub->setParent($this->getFixture('salary'));
        $this->em->persist($sub);
        $weekend = $this->series('test_user', 'salary', 'checking', 'Salaire week-end', 'WEEKCO', 50000);
        $weekend->setCategory($sub);
        $this->em->flush();

        $data = $this->read();

        self::assertSame(545000, $data['monthlyCents']);
        self::assertSame(['Salaire ACME', 'Salaire week-end'], array_column($data['operations'], 'label'));
    }

    public function testAMeasuredSalaryIsTheAverageOfItsLastTwelvePayments(): void
    {
        // Thirteen months back an old pay, out of the window; then twelve at 4 950 €.
        $this->attach('measured_op', 'measured_checking', 300000, 13);
        for ($monthsAgo = 12; $monthsAgo >= 1; --$monthsAgo) {
            $this->attach('measured_op', 'measured_checking', 495000, $monthsAgo);
        }

        $data = $this->read('measured_user');

        self::assertSame(495000, $data['monthlyCents']);
        self::assertSame('measured', $data['source']);
        self::assertSame(12, $data['operations'][0]['paymentCount']);
    }

    /** A late salary arrives with the next one: two payments the same day, still one salary a month. */
    public function testTwoMeasuredPaymentsTheSameDayAverageOneSalary(): void
    {
        $today = new \DateTimeImmutable('today');
        $this->attach('measured_op', 'measured_checking', 495000, 2);
        $this->attach('measured_op', 'measured_checking', 495000, 1, bookedAt: $today);
        $this->attach('measured_op', 'measured_checking', 495000, 0, bookedAt: $today);

        self::assertSame(495000, $this->read('measured_user')['monthlyCents']);
    }

    public function testAnExceptionalCreditOrANeutralMovementIsNotAPayment(): void
    {
        $this->attach('measured_op', 'measured_checking', 495000, 3);
        $this->attach('measured_op', 'measured_checking', 495000, 2);
        $this->attach('measured_op', 'measured_checking', 900000, 1)->setIsExceptional(true);
        $this->attach('measured_op', 'measured_checking', 700000, 0)->setTransferKind(TransferKind::Internal);
        $this->flushWithoutTransactionEffects($this->em);

        $data = $this->read('measured_user');

        self::assertSame(495000, $data['monthlyCents']);
        self::assertSame(2, $data['operations'][0]['paymentCount']);
    }

    /** Measured but never paid yet: its reference is the only figure there is. */
    public function testAMeasuredSeriesWithNoPaymentFallsBackOnItsReference(): void
    {
        $data = $this->read('measured_user');

        self::assertSame(500000, $data['monthlyCents']);
        self::assertSame('expected', $data['source']);
        self::assertSame(0, $data['operations'][0]['paymentCount']);
    }

    public function testTheDeclaredSalaryWins(): void
    {
        $data = $this->read('declared_user');

        self::assertSame(520000, $data['monthlyCents']);
        self::assertSame('declared', $data['source']);
        self::assertSame(520000, $data['declaredMonthlyCents']);
        self::assertSame(['Salaire Initech'], array_column($data['operations'], 'label'));
    }

    public function testNothingAtAllIsUnknownNeverZero(): void
    {
        $data = $this->read('empty_user');

        self::assertNull($data['monthlyCents']);
        self::assertSame('unknown', $data['source']);
        self::assertFalse($data['isConfigured']);
        self::assertSame([], $data['operations']);
    }

    public function testItIsAPureRead(): void
    {
        $before = $this->em->getRepository(RecurringOperation::class)->count([]);

        $this->read();
        $this->read('measured_user');

        $this->em->clear();
        self::assertSame($before, $this->em->getRepository(RecurringOperation::class)->count([]));
        self::assertSame(495000, $this->em->find(RecurringOperation::class, $this->getFixture('salary_op')->getId())->getReferenceAmountCents());
    }

    private function series(string $user, string $category, string $account, string $label, string $counterparty, int $cents, RecurrencePeriod $period = RecurrencePeriod::Monthly): RecurringOperation
    {
        $operation = new RecurringOperation();
        $operation->setUser($this->getFixture($user));
        $operation->setCategory($this->getFixture($category));
        $operation->setAccount($this->getFixture($account));
        $operation->setLabel($label);
        $operation->setCounterpartyName($counterparty);
        $operation->setPeriod($period);
        $operation->setAnchorOn(new \DateTimeImmutable('first day of this month -12 months'));
        $operation->setReferenceAmountCents($cents);
        $operation->setReferenceSource(ReferenceAmountSource::Declared);
        $this->em->persist($operation);
        $this->em->flush();

        return $operation;
    }

    /**
     * A payment attached to the occurrence of `$monthsAgo` months back (the
     * 25th), booked that day unless told otherwise. A bonus is filed in
     * « Primes » and attached to nothing.
     */
    private function attach(string $operation, string $account, int $cents, int $monthsAgo, ?\DateTimeImmutable $bookedAt = null, bool $isBonus = false): Transaction
    {
        /** @var RecurringOperation $series */
        $series = $this->getFixture($operation);
        /** @var Account $owner */
        $owner = $this->getFixture($account);
        $occurrenceOn = new \DateTimeImmutable(sprintf('first day of this month -%d months +24 days', $monthsAgo));

        $transaction = new Transaction();
        $transaction->setUser($owner->getUser());
        $transaction->setAccount($owner);
        $transaction->setAmountCents($cents);
        $transaction->setBookedAt($bookedAt ?? $occurrenceOn);
        $transaction->setLabel($isBonus ? 'VIR ACME PRIME' : 'VIR '.$series->getCounterpartyName());

        if ($isBonus) {
            $transaction->qualify(Qualification::none($this->getFixture('bonus')), null);
        } else {
            $transaction->setCounterpartyName($series->getCounterpartyName());
            $transaction->attachToRecurring($series, $occurrenceOn, RecurringLinkSource::Manual);
        }

        $this->em->persist($transaction);
        $this->flushWithoutTransactionEffects($this->em);

        return $transaction;
    }
}
