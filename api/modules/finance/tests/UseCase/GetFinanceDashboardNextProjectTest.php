<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\UseCase\GetFinanceDashboard;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * The dashboard's `nextProject` (MAG-529): the first project of the planning,
 * the two after it, and the delay the month's overspending causes. The
 * month-by-month rules are ProjectPlannerTest's. Read on 2026-10-21.
 *
 * Exemption: N/A — DB state, Mercure and Elasticsearch, it is a pure read.
 */
class GetFinanceDashboardNextProjectTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private GetFinanceDashboard $useCase;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-10-21 12:00:00 UTC'));

        $this->useCase = self::getContainer()->get(GetFinanceDashboard::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures(__DIR__.'/../Api/fixtures/next_project.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed>|null */
    private function nextProject(int $month = 10, string $as = 'test_user'): ?array
    {
        return $this->useCase->execute($this->getFixture($as), 2026, $month)['nextProject'];
    }

    public function testWithoutAProjectThereIsNoNextProject(): void
    {
        self::assertNull($this->nextProject(10, 'lonely_user'));
    }

    public function testItIsTheFirstProjectOfThePlanningWithWhatIsGatheredAndTheNextOnesFollow(): void
    {
        $next = $this->nextProject();

        self::assertSame('Vélo', $next['name']);
        self::assertSame(100000, $next['amountCents']);
        self::assertSame(74000, $next['gatheredCents']);
        self::assertSame('2026-10', $next['purchaseMonth']);
        self::assertSame(
            [['projectId' => (string) $this->getFixture('japan')->getId(), 'name' => 'Voyage au Japon', 'amountCents' => 319000, 'purchaseMonth' => '2027-04']],
            $next['following'],
        );
    }

    public function testTheOverspendingOfTheMonthPushesTheJapanTripBackOneMonth(): void
    {
        $delay = $this->nextProject()['delay'];

        self::assertSame((string) $this->getFixture('japan')->getId(), $delay['projectId']);
        self::assertSame('Voyage au Japon', $delay['name']);
        self::assertSame(1, $delay['months']);
        self::assertSame(5800, $delay['overspentCents']);
        self::assertSame(['Restaurants', 'Obsydian'], $delay['envelopes']);
        self::assertSame('2027-05', $delay['newPurchaseMonth']);
    }

    public function testWithoutOverspendingThereIsNoDelay(): void
    {
        $this->getFixture('restaurants_spend')->setAmountCents(-15000);
        $this->getFixture('obsydian_spend')->setAmountCents(-5000);
        $this->em->flush();

        self::assertNull($this->nextProject()['delay']);
    }

    public function testOverspendingThatMovesNothingIsNoDelay(): void
    {
        // 600 € of margin: 58 € less this month does not move the purchase.
        $this->getFixture('japan')->setAmountCents(300000);
        $this->em->flush();

        self::assertNull($this->nextProject()['delay']);
    }

    public function testAMonthThatIsNotTheOneUnderWayCarriesNoDelay(): void
    {
        self::assertNull($this->nextProject(9)['delay']);
    }

    public function testAProjectBeyondTheHorizonIsNotTheNextProject(): void
    {
        /** @var Project $bike */
        $bike = $this->getFixture('bike');
        $bike->setAmountCents(100000000);
        $this->getFixture('japan')->setAmountCents(100000000);
        $this->em->flush();

        self::assertNull($this->nextProject());
    }
}
