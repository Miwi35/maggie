<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\UseCase\GetRecurringOccurrences;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The occurrences the confirmed series still expect (MAG-307): computed from
 * the schedule, minus those a transaction is attached to — and nothing else
 * hides one. A pure read: the lines are the same before and after.
 *
 * The rent is 900 € on the 5th, anchored on 2027-01-05.
 */
class GetRecurringOccurrencesTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use RecurringProjectionsWorld;

    private GetRecurringOccurrences $occurrences;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->occurrences = self::getContainer()->get(GetRecurringOccurrences::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('recurring_projections.yaml');
    }

    /** @return list<array<string, mixed>> */
    private function read(string $from, string $until, string $as = 'test_user'): array
    {
        $user = $this->getFixture($as);
        \assert($user instanceof User);

        return $this->occurrences->execute($user, new \DateTimeImmutable($from), new \DateTimeImmutable($until));
    }

    public function testThreeMonthsOfAMonthlySeriesAreRenderedWithTheirFields(): void
    {
        $rent = $this->series('Loyer', -90000, '2027-01-05');

        $occurrences = $this->read('2027-05-20', '2027-08-20');

        self::assertSame(['2027-06-05', '2027-07-05', '2027-08-05'], array_column($occurrences, 'date'));
        self::assertSame([
            'date' => '2027-06-05',
            'recurringOperationId' => (string) $rent->getId(),
            'label' => 'Loyer',
            'categoryId' => (string) $this->getFixture('housing')->getId(),
            'categoryName' => 'Logement',
            'accountId' => (string) $this->getFixture('checking')->getId(),
            'amountCents' => -90000,
            'currency' => 'EUR',
        ], $occurrences[0]);
    }

    public function testTheWindowIsHalfOpen(): void
    {
        $this->series('Loyer', -90000, '2027-01-05');

        self::assertSame(['2027-06-05'], array_column($this->read('2027-06-05', '2027-07-05'), 'date'));
        self::assertSame([], $this->read('2027-06-06', '2027-07-05'));
    }

    public function testAnEndedSeriesStopsAtItsLastOccurrence(): void
    {
        $this->series('Ancienne salle', -3000, '2027-01-10', endsOn: '2027-06-30');

        self::assertSame(['2027-05-10', '2027-06-10'], array_column($this->read('2027-05-01', '2027-09-01'), 'date'));
        self::assertSame([], $this->read('2027-07-01', '2027-09-01'));
    }

    public function testTheWindowIsBoundedTo400Days(): void
    {
        $this->series('Loyer', -90000, '2027-01-05');

        self::assertCount(13, $this->read('2027-01-01', '2028-02-05'), 'exactly 400 days is allowed');

        $this->expectException(\InvalidArgumentException::class);
        $this->read('2027-01-01', '2028-02-06');
    }

    public function testAnEmptyOrInvertedWindowIsEmpty(): void
    {
        $this->series('Loyer', -90000, '2027-01-05');

        self::assertSame([], $this->read('2027-06-05', '2027-06-05'));
        self::assertSame([], $this->read('2027-07-05', '2027-06-05'));
    }

    #[DataProvider('settledStatuses')]
    public function testAnOccurrenceWithAnAttachedTransactionDisappears(TransactionStatus $status): void
    {
        $rent = $this->series('Loyer', -90000, '2027-01-05');
        $this->settle($rent, '2027-06-05', -90000, $status);

        self::assertSame(['2027-07-05', '2027-08-05'], array_column($this->read('2027-06-01', '2027-08-20'), 'date'));
    }

    /** @return iterable<string, array{TransactionStatus}> */
    public static function settledStatuses(): iterable
    {
        yield 'booked' => [TransactionStatus::Spent];
        yield 'committed' => [TransactionStatus::Committed];
        yield 'planned, entered in advance' => [TransactionStatus::Planned];
    }

    public function testALineBookedAnotherDayStillSettlesItsOccurrence(): void
    {
        $rent = $this->series('Loyer', -90000, '2027-01-05');
        $this->settle($rent, '2027-06-05', -91000, TransactionStatus::Spent, bookedAt: '2027-06-08');

        self::assertSame(['2027-07-05'], array_column($this->read('2027-06-01', '2027-07-20'), 'date'));
    }

    public function testALineOfTheRightAmountThatIsNotAttachedDoesNotHideTheOccurrence(): void
    {
        $this->series('Loyer', -90000, '2027-01-05');
        $this->add('checking', -90000, '2027-06-05', label: 'LOYER');

        self::assertSame(['2027-06-05'], array_column($this->read('2027-06-01', '2027-06-20'), 'date'));
    }

    public function testItIsolatesUsersAndWritesNothing(): void
    {
        $this->series('Loyer', -90000, '2027-01-05');
        $this->series('Loyer de l\'autre', -50000, '2027-01-05', 'other_checking', 'other_housing');
        $before = $this->em->getRepository(Transaction::class)->count([]);

        self::assertSame(['Loyer'], array_unique(array_column($this->read('2027-06-01', '2027-08-20'), 'label')));
        self::assertSame(["Loyer de l'autre"], array_unique(array_column($this->read('2027-06-01', '2027-08-20', 'other_user'), 'label')));
        self::assertSame($before, $this->em->getRepository(Transaction::class)->count([]));
    }

    private function add(string $accountRef, int $amountCents, string $bookedAt, string $label): void
    {
        $account = $this->getFixture($accountRef);
        $transaction = (new Transaction())
            ->setUser($account->getUser())
            ->setAccount($account)
            ->setAmountCents($amountCents)
            ->setBookedAt(new \DateTimeImmutable($bookedAt))
            ->setLabel($label);
        $this->em->persist($transaction);
        $this->flushWithoutTransactionEffects($this->em);
    }
}
