<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Enum\PlacementFrequency;
use Maggie\Finance\Mcp\Tool\ManagePlacementsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** manage_placements (MAG-555): every action, and partial requests refused with `current`. */
class PlacementToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/placement.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManagePlacementsTool::class);
        \assert($tool instanceof ManagePlacementsTool);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function reload(string $fixture): ?Placement
    {
        return $this->em()->find(Placement::class, $this->id($fixture));
    }

    private function rows(): int
    {
        return $this->em()->getRepository(Placement::class)->count([]);
    }

    public function testWithoutAUserItAnswersAnError(): void
    {
        $data = $this->call('list');

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('placements', $data);
    }

    public function testAnUnknownActionIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('archive');

        self::assertStringContainsString('Unknown action', $data['error']);
    }

    public function testListGivesOnlyTheUsersPlacementsWithTheirFigures(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('list');

        self::assertSame(['Assurance-vie', 'PEA'], array_column($data['placements'], 'name'));
        $pea = $data['placements'][1];
        self::assertSame($this->id('pea'), $pea['id']);
        self::assertEqualsWithDelta(7443007, $pea['final'], 100);
        self::assertSame(200000 + 240 * 15000, $pea['paid']);
        self::assertSame($pea['final'] - $pea['paid'], $pea['interest']);
        self::assertArrayHasKey('rent', $pea);
    }

    public function testCreatePersistsPublishesAndIndexes(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'Livret', initial: 50000, deposit: 0, frequency: 'once', annualRate: 300, years: 10);

        self::assertTrue($data['success']);
        self::assertEqualsWithDelta(67196, $data['placement']['final'], 100);

        $placement = $this->em()->getRepository(Placement::class)->findOneBy(['name' => 'Livret']);
        self::assertInstanceOf(Placement::class, $placement);
        self::assertSame(PlacementFrequency::Once, $placement->getFrequency());
        self::assertSame(50000, $placement->getInitial());
        self::assertSame(300, $placement->getAnnualRate());
        self::assertSame($this->id('test_user'), (string) $placement->getUser()->getId());

        $this->assertMercureUpdatePublished('/api/finance/placements/'.$placement->getId());
        $this->assertElasticsearchIndexDispatched(Placement::class);
    }

    public function testAPartialCreateIsRefusedWithWhatWasGiven(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'PEA bis', deposit: 15000, frequency: 'monthly');

        self::assertStringContainsString('initial, annualRate, years', $data['error']);
        self::assertSame(['name' => 'PEA bis', 'deposit' => 15000, 'frequency' => 'monthly'], $data['current']);
        self::assertSame(3, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testACreateWithNothingPaidIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'Vide', initial: 0, deposit: 0, frequency: 'monthly', annualRate: 300, years: 10);

        self::assertSame(Placement::NOTHING_PAID, $data['error']);
        self::assertSame('Vide', $data['current']['name']);
        self::assertSame(3, $this->rows());
    }

    public function testAnUnknownFrequencyIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'PEA', initial: 0, deposit: 100, frequency: 'weekly', annualRate: 300, years: 10);

        self::assertStringContainsString('Unknown frequency', $data['error']);
        self::assertSame(3, $this->rows());
    }

    public function testUpdateChangesOnlyWhatIsGiven(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', placementId: $this->id('pea'), annualRate: 500);

        self::assertTrue($data['success']);
        $pea = $this->reload('pea');
        self::assertInstanceOf(Placement::class, $pea);
        self::assertSame(500, $pea->getAnnualRate());
        self::assertSame(15000, $pea->getDeposit());
        self::assertSame(20, $pea->getYears());
        $this->assertMercureUpdatePublished('/api/finance/placements/'.$this->id('pea'));
        $this->assertElasticsearchIndexDispatched(Placement::class);
    }

    public function testARefusedUpdateAnswersThePlacementAsItIs(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', placementId: $this->id('pea'), years: 99);

        self::assertStringContainsString('years', $data['error']);
        self::assertSame(20, $data['current']['years']);
        self::assertSame(20, $this->reload('pea')?->getYears());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnotherUsersPlacementIsNotFound(): void
    {
        $this->loginFixtureUser();

        $update = $this->call('update', placementId: $this->id('secret'), name: 'Volé');
        $delete = $this->call('delete', placementId: $this->id('secret'));
        $simulate = $this->call('simulate', placementId: $this->id('secret'));

        self::assertStringContainsString('not found', $update['error']);
        self::assertStringContainsString('not found', $delete['error']);
        self::assertStringContainsString('not found', $simulate['error']);
        self::assertSame('Placement secret', $this->reload('secret')?->getName());
    }

    public function testDeleteRemovesPublishesAndUnindexes(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('delete', placementId: $this->id('life'));

        self::assertTrue($data['success']);
        self::assertNull($this->reload('life'));
        $this->assertMercureDeletePublished('/api/finance/placements/'.$this->id('life'));
        $this->assertElasticsearchDeleteDispatched('placements');
    }

    public function testSimulateASavedPlacementYearByYear(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('simulate', placementId: $this->id('pea'));

        self::assertSame('PEA', $data['placement']['name']);
        self::assertCount(21, $data['simulation']['curve']);
        self::assertSame(['year' => 0, 'capital' => 200000, 'paid' => 200000], $data['simulation']['curve'][0]);
        self::assertSame($data['simulation']['final'], $data['simulation']['curve'][20]['capital']);
    }

    public function testSimulateAWhatIfSavesNothing(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('simulate', initial: 0, deposit: 10000, frequency: 'monthly', annualRate: 500, years: 10);

        self::assertEqualsWithDelta(1543632, $data['simulation']['final'], 100);
        self::assertCount(11, $data['simulation']['curve']);
        self::assertSame(3, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testAPartialWhatIfIsRefusedWithWhatWasGiven(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('simulate', deposit: 10000, annualRate: 500);

        self::assertStringContainsString('initial, frequency, years', $data['error']);
        self::assertSame(['deposit' => 10000, 'annualRate' => 500], $data['current']);
    }
}
