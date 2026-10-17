<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\UseCase\GetAccountTimeline;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * An account over a month, day by day (MAG-422): the recurring side is what the
 * recurring overview computes, so the world is `recurring_overview.yaml`, read
 * on 2027-05-20.
 */
class GetAccountTimelineTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private GetAccountTimeline $timeline;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->timeline = self::getContainer()->get(GetAccountTimeline::class);
        $this->loadFixtures('recurring_overview.yaml');
    }

    /** @return array{month: string, previousMonth: ?string, account: array<string, mixed>, days: list<array<string, mixed>>} */
    private function read(string $month): array
    {
        /** @var Account $account */
        $account = $this->getFixture('checking');

        return $this->timeline->execute($account, new \DateTimeImmutable($month.'-01'), new \DateTimeImmutable('2027-05-20'));
    }

    /** @return array<string, mixed> */
    private function day(string $month, string $date): array
    {
        foreach ($this->read($month)['days'] as $day) {
            if ($day['date'] === $date) {
                return $day;
            }
        }

        self::fail(sprintf('The timeline of %s has no day %s.', $month, $date));
    }

    public function testDaysComeNewestFirstWithTheirLinesAndTheAccount(): void
    {
        $timeline = $this->read('2027-03');

        self::assertSame('2027-03', $timeline['month']);
        self::assertSame('Courant', $timeline['account']['name']);
        self::assertSame('Crédit Agricole', $timeline['account']['bank']);
        self::assertSame(125000, $timeline['account']['balanceCents']);
        self::assertTrue($timeline['account']['isManual']);

        $dates = array_column($timeline['days'], 'date');
        $sorted = $dates;
        rsort($sorted);
        self::assertSame($sorted, $dates);
        self::assertContains('2027-03-12', $dates);
        self::assertNotContains('2027-04-12', $dates);

        $line = $this->day('2027-03', '2027-03-12')['transactions'][0];
        self::assertSame('CB FLIXO 12/03', $line['label']);
        self::assertSame(-1349, $line['amountCents']);
        self::assertSame('spent', $line['status']);
        self::assertSame('none', $line['transferKind']);
        self::assertNull($line['counterpartAccount']);
    }

    public function testThePreviousMonthIsTheLatestMonthWithALine(): void
    {
        self::assertSame('2027-03', $this->read('2027-04')['previousMonth']);
        self::assertSame('2027-01', $this->read('2027-02')['previousMonth']);
        self::assertNull($this->read('2027-01')['previousMonth']);
    }

    public function testThePriceRiseIsOnTheLineWhereItBegan(): void
    {
        $began = $this->day('2027-04', '2027-04-12')['transactions'][0];
        self::assertSame(['percent' => 33, 'previousCents' => -1349], $began['recurringIncrease']);

        $after = $this->day('2027-05', '2027-05-13')['transactions'][0];
        self::assertNull($after['recurringIncrease']);
        self::assertNotNull($after['recurringOperationId']);

        self::assertNull($this->day('2027-03', '2027-03-12')['transactions'][0]['recurringIncrease']);
    }

    public function testAMissedDueDateAppearsOnItsDay(): void
    {
        $rent = (string) $this->getFixture('rent')->getId();

        self::assertSame(
            [['recurringOperationId' => $rent, 'label' => 'Loyer', 'expectedOn' => '2027-04-30', 'amountCents' => -90000]],
            $this->day('2027-04', '2027-04-30')['missedOccurrences'],
        );
        self::assertSame([], $this->day('2027-04', '2027-04-30')['transactions']);

        // Only the latest gap of a series is news: April's rent is not repeated in May.
        $labels = array_merge(...array_map(
            static fn (array $day) => array_column($day['missedOccurrences'], 'label'),
            $this->read('2027-05')['days'],
        ));
        self::assertNotContains('Loyer', $labels);
    }
}
