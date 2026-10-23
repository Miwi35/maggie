<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Entity\ProjectFundingSource;
use Maggie\Finance\Entity\SafetyCushion;
use Maggie\Finance\Entity\Scenario;
use Maggie\Finance\Enum\FundingSourceKind;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * GET /api/finance/placements/{id}/simulation: the curve the clients draw
 * (MAG-555), with and without the capital a scenario's projects take out of it
 * (MAG-556). Read on 2026-10-21, M0 is October 2026.
 */
class PlacementSimulationControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        Clock::set(new MockClock('2026-10-21 12:00:00 UTC'));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/scenario.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function simulate(string $id, bool $authenticated = true, string $query = ''): array
    {
        if ($authenticated) {
            $this->authenticateAsUser($this->getFixture('test_user'));
        }

        $this->client->request('GET', \sprintf('/api/finance/placements/%s/simulation%s', $id, $query), [], [], $authenticated ? $this->authHeaders() : []);

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->simulate((string) $this->getFixture('pea')->getId(), authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testThePeaMonthByMonth(): void
    {
        $data = $this->simulate((string) $this->getFixture('pea')->getId());

        self::assertResponseIsSuccessful();
        self::assertEqualsWithDelta(7443007, $data['final'], 100);
        self::assertSame(200000 + 240 * 15000, $data['paid']);
        self::assertCount(241, $data['curve']);
        self::assertSame(['month' => 0, 'capital' => 200000, 'paid' => 200000], $data['curve'][0]);
        self::assertSame($data['final'], $data['curve'][240]['capital']);
    }

    public function testWithoutAnyWithdrawalBothCurvesAreTheSame(): void
    {
        $data = $this->simulate((string) $this->getFixture('pea')->getId());

        self::assertSame([], $data['withdrawals']);
        self::assertSame(0, $data['withdrawn']);
        self::assertSame(0, $data['gapAtEnd']);
        self::assertSame($data['curve'], $data['curveWithout']);
        self::assertSame($data['curve'], $data['curveWith']);
        self::assertSame($data['final'], $data['finalWith']);
    }

    public function testAProjectFundedByThePeaTakesItsCapitalOutOfTheCurve(): void
    {
        $this->fundJapanByThePea(150000);

        $data = $this->simulate((string) $this->getFixture('pea')->getId());

        self::assertResponseIsSuccessful();
        self::assertCount(1, $data['withdrawals']);
        $withdrawal = $data['withdrawals'][0];
        self::assertSame((string) $this->getFixture('japan')->getId(), $withdrawal['projectId']);
        self::assertSame('Voyage au Japon', $withdrawal['name']);
        self::assertSame(150000, $withdrawal['amountCents']);
        self::assertSame('2027-04', $withdrawal['month']);
        self::assertSame(150000, $data['withdrawn']);

        self::assertCount(241, $data['curveWithout']);
        self::assertCount(241, $data['curveWith']);
        self::assertSame($data['curve'], $data['curveWithout']);
        self::assertSame(\array_slice($data['curveWithout'], 0, 6), \array_slice($data['curveWith'], 0, 6), 'identical until April 2027, month 6');
        self::assertSame($data['curveWithout'][6]['capital'] - 150000, $data['curveWith'][6]['capital']);
        self::assertSame($data['final'] - $data['finalWith'], $data['gapAtEnd']);
        self::assertGreaterThan(150000, $data['gapAtEnd'], 'what is taken out also stops earning interest');
    }

    public function testTheWithdrawalsListedAreWhatTheCurveReallyLost(): void
    {
        // 4 000 € asked of a PEA that holds less when the trip is bought.
        $this->fundJapanByThePea(400000);

        $data = $this->simulate((string) $this->getFixture('pea')->getId());

        self::assertCount(1, $data['withdrawals']);
        self::assertLessThan(400000, $data['withdrawals'][0]['amountCents']);
        self::assertSame($data['withdrawn'], $data['withdrawals'][0]['amountCents']);
        self::assertSame($data['withdrawn'], array_sum(array_column($data['withdrawals'], 'amountCents')));
        $month = (int) substr($data['withdrawals'][0]['month'], 5, 2) - 10 + 12 * ((int) substr($data['withdrawals'][0]['month'], 0, 4) - 2026);
        self::assertLessThanOrEqual(1, $data['curveWith'][$month]['capital'], 'the PEA is emptied the month the trip is bought');
    }

    public function testTheScenarioDecidesWhichProjectsTakeCapital(): void
    {
        $this->fundJapanByThePea(150000);
        $id = (string) $this->getFixture('pea')->getId();
        $em = $this->em();

        $current = $this->simulate($id, query: '?scenarioId='.$this->getFixture('mine')->getId());
        self::assertResponseIsSuccessful();
        self::assertCount(1, $current['withdrawals']);

        $mine = $em->find(Scenario::class, (string) $this->getFixture('mine')->getId());
        \assert($mine instanceof Scenario);
        $mine->setProjects([['projectId' => (string) $this->getFixture('dentist')->getId(), 'position' => 0]]);
        $em->flush();

        $other = $this->simulate($id, query: '?scenarioId='.$this->getFixture('mine')->getId());
        self::assertResponseIsSuccessful();
        self::assertSame([], $other['withdrawals'], 'Japan is not in this scenario');
        self::assertSame(0, $other['gapAtEnd']);
    }

    public function testAnUnknownOrForeignScenarioIs404(): void
    {
        $id = (string) $this->getFixture('pea')->getId();

        $this->simulate($id, query: '?scenarioId='.$this->getFixture('theirs')->getId());
        self::assertResponseStatusCodeSame(404);

        $this->simulate($id, query: '?scenarioId=01JZZZZZZZZZZZZZZZZZZZZZZZ');
        self::assertResponseStatusCodeSame(404);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }

    /** No cushion to build, no loan to pay: the capacity is free, and the current scenario is Japan alone, part of it paid from the PEA. */
    private function fundJapanByThePea(int $cents): void
    {
        $em = $this->em();
        $cushion = $this->getFixture('cushion');
        \assert($cushion instanceof SafetyCushion);
        $cushion->setTargetMonths(0);
        $em->remove($this->getFixture('consumer'));

        $japan = $this->getFixture('japan');
        \assert($japan instanceof Project);
        $source = (new ProjectFundingSource())
            ->setKind(FundingSourceKind::Capital)
            ->setAmountCents($cents)
            ->setPlacement($this->getFixture('pea'))
            ->setProject($japan);
        $em->persist($source);

        $mine = $em->find(Scenario::class, (string) $this->getFixture('mine')->getId());
        \assert($mine instanceof Scenario);
        $mine->setProjects([['projectId' => (string) $japan->getId(), 'position' => 0]])->setCurrent(true);
        $em->flush();
    }

    public function testAnotherUsersPlacementIsNotFound(): void
    {
        $this->simulate((string) $this->getFixture('secret')->getId());

        self::assertResponseStatusCodeSame(404);
    }

    public function testAnIdThatIsNotAUlidIsNotFound(): void
    {
        $this->simulate('not-a-ulid');

        self::assertResponseStatusCodeSame(404);
    }
}
