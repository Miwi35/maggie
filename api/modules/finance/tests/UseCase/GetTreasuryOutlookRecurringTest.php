<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\UseCase\GetTreasuryOutlook;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The occurrences of the confirmed recurring series enter the treasury
 * projection day by day (MAG-307), next to the planned and committed lines:
 * a rent expected in four days pulls the forecast overdraft forward, and the
 * same rent already attached to a line is counted once.
 *
 * Read on 2026-10-25 with the salary awaited since the 25th of September, so
 * the projection runs to 2026-11-24. The rent is 900 €, due on the 29th.
 */
class GetTreasuryOutlookRecurringTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use RecurringProjectionsWorld;

    private const string TODAY = '2026-10-25';

    private GetTreasuryOutlook $outlook;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->outlook = self::getContainer()->get(GetTreasuryOutlook::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('recurring_projections.yaml');

        $checking = $this->getFixture('checking');
        \assert($checking instanceof Account);
        $checking->setBalanceCents(20000);
        $this->em->flush();
    }

    /** @return array<string, mixed> */
    private function read(string $as = 'test_user'): array
    {
        return $this->outlook->execute($this->getFixture($as), new \DateTimeImmutable(self::TODAY));
    }

    /** @return array<string, mixed> */
    private function rowOn(array $outlook, string $date): array
    {
        foreach ($outlook['projection'] as $row) {
            if ($row['date'] === $date) {
                return $row;
            }
        }
        self::fail("No projection row on {$date}");
    }

    public function testARentExpectedInFourDaysBringsTheOverdraftForward(): void
    {
        $rent = $this->series('Loyer', -90000, '2026-07-29');

        $outlook = $this->read();

        self::assertTrue($outlook['overdraft']['isForecast']);
        self::assertSame('2026-10-29', $outlook['overdraft']['onDate']);
        self::assertSame(-70000, $outlook['overdraft']['worstBalanceCents']);

        $day = $this->rowOn($outlook, '2026-10-29');
        self::assertSame(-70000, $day['balanceCents']);
        self::assertSame([[
            'id' => (string) $rent->getId(),
            'label' => 'Loyer',
            'amountCents' => -90000,
            'status' => 'expected',
            'accountId' => (string) $this->getFixture('checking')->getId(),
            'accountName' => 'Courant',
            'source' => 'recurring',
        ]], $day['movements']);
        self::assertSame(20000, $this->rowOn($outlook, '2026-10-28')['balanceCents']);
    }

    public function testWithoutTheSeriesNothingIsForecast(): void
    {
        self::assertFalse($this->read()['overdraft']['isForecast']);
    }

    #[DataProvider('enteredStatuses')]
    public function testTheSameRentAlreadyAttachedIsCountedOnce(TransactionStatus $status): void
    {
        $rent = $this->series('Loyer', -90000, '2026-07-29');
        $line = $this->settle($rent, '2026-10-29', -90000, $status);

        $outlook = $this->read();

        $day = $this->rowOn($outlook, '2026-10-29');
        self::assertSame(-70000, $day['balanceCents'], 'one rent, not two');
        self::assertCount(1, $day['movements']);
        self::assertSame('transaction', $day['movements'][0]['source']);
        self::assertSame((string) $line->getId(), $day['movements'][0]['id']);
    }

    /** @return iterable<string, array{TransactionStatus}> */
    public static function enteredStatuses(): iterable
    {
        yield 'planned' => [TransactionStatus::Planned];
        yield 'committed' => [TransactionStatus::Committed];
    }

    public function testEveryBalanceStaysThePreviousOnePlusTheDaysMovementsWhateverTheirSource(): void
    {
        $this->series('Loyer', -90000, '2026-07-29');
        $this->series('Mutuelle', 4500, '2026-07-29');
        $rent = $this->series('Salle', -3000, '2026-07-27');
        $this->settle($rent, '2026-10-27', -2500, TransactionStatus::Planned);

        $outlook = $this->read();

        $previous = $outlook['balanceCents'];
        foreach ($outlook['projection'] as $row) {
            self::assertSame($previous + array_sum(array_column($row['movements'], 'amountCents')), $row['balanceCents'], $row['date']);
            $previous = $row['balanceCents'];
        }
        self::assertSame(['Loyer', 'Mutuelle'], array_column($this->rowOn($outlook, '2026-10-29')['movements'], 'label'));
        self::assertSame(['recurring', 'recurring'], array_column($this->rowOn($outlook, '2026-10-29')['movements'], 'source'));
        self::assertSame(['transaction'], array_column($this->rowOn($outlook, '2026-10-27')['movements'], 'source'));
    }

    public function testASeriesOnASavingsAccountIsNotInTheCheckingProjection(): void
    {
        $this->series('Virement épargne', -50000, '2026-07-29', 'livret', 'leisure');

        $outlook = $this->read();

        self::assertFalse($outlook['overdraft']['isForecast']);
        self::assertSame([], $this->rowOn($outlook, '2026-10-29')['movements']);
    }

    public function testAnEndedSeriesIsNotProjected(): void
    {
        $this->series('Ancien loyer', -90000, '2026-07-29', endsOn: '2026-09-30');

        self::assertFalse($this->read()['overdraft']['isForecast']);
    }

    public function testAnotherUsersSeriesIsNotRead(): void
    {
        $this->series('Loyer de l\'autre', -90000, '2026-07-29', 'other_checking', 'other_housing');

        self::assertFalse($this->read()['overdraft']['isForecast']);
    }
}
