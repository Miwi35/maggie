<?php

namespace Maggie\Health\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Enum\MeasurementSource;
use Maggie\Health\Mcp\Tool\ManageBodyMeasurementsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** manage_body_measurements (MAG-706): every action, « Je pèse 72,4 kg » first. */
class BodyMeasurementToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use SecurityTokenTrait;

    private const ROWS = 7;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-24T06:45:00+00:00')));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/body_measurement.yaml');
        $this->resetMercure();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageBodyMeasurementsTool::class);
        \assert($tool instanceof ManageBodyMeasurementsTool);

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

    private function reload(string $fixture): ?BodyMeasurement
    {
        return $this->em()->find(BodyMeasurement::class, $this->id($fixture));
    }

    private function rows(): int
    {
        return $this->em()->getRepository(BodyMeasurement::class)->count([]);
    }

    public function testWithoutAUserItAnswersAnError(): void
    {
        $data = $this->call('list');

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('measurements', $data);
    }

    public function testAnUnknownActionIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('Unknown action', $this->call('archive')['error']);
    }

    /** « Je pèse 72,4 kg »: the weight alone, the moment is now, the source manual. */
    public function testACreateWithOnlyTheWeightIsTakenNowAndManual(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', weightKg: 72.4);

        self::assertTrue($data['success']);
        self::assertSame(72.4, $data['measurement']['weightKg']);
        self::assertSame('manual', $data['measurement']['source']);
        self::assertSame('Saisie', $data['measurement']['sourceLabel']);
        self::assertFalse($data['measurement']['excluded']);

        $measurement = $this->em()->getRepository(BodyMeasurement::class)->find($data['measurement']['id']);
        self::assertInstanceOf(BodyMeasurement::class, $measurement);
        self::assertEquals(new \DateTimeImmutable('2026-10-24T06:45:00+00:00'), $measurement->getMeasuredAt());
        self::assertSame(72.4, $measurement->getWeightKg());
        self::assertNull($measurement->getBodyFatPercent());
        self::assertSame(MeasurementSource::Manual, $measurement->getSource());
        self::assertSame($this->id('test_user'), (string) $measurement->getUser()->getId());
        $this->assertMercureUpdatePublished('/api/body-measurements/'.$measurement->getId());
    }

    public function testACreateWithAMomentKeepsIt(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', measuredAt: '2026-10-20T07:30:00+02:00', weightKg: 72.9, bodyFatPercent: 21.5);

        self::assertTrue($data['success']);
        self::assertEquals(new \DateTimeImmutable('2026-10-20T07:30:00+02:00'), new \DateTimeImmutable($data['measurement']['measuredAt']));
        self::assertSame(21.5, $data['measurement']['bodyFatPercent']);
    }

    public function testACreateWithoutAnyFigureOrOutOfBoundsIsRefusedNamingTheField(): void
    {
        $this->loginFixtureUser();

        self::assertSame('weightKg', $this->call('create')['field']);
        self::assertSame('weightKg', $this->call('create', weightKg: 0.0)['field']);
        self::assertSame('weightKg', $this->call('create', weightKg: 724.0)['field']);
        self::assertSame('bodyFatPercent', $this->call('create', weightKg: 72.4, bodyFatPercent: 100.0)['field']);
        self::assertSame('waistCm', $this->call('create', waistCm: 301.0)['field']);
        self::assertSame('measuredAt', $this->call('create', measuredAt: '2026-10-20', weightKg: 72.4)['field']);
        self::assertSame('source', $this->call('create', weightKg: 72.4, source: 'scale')['field']);

        self::assertSame(self::ROWS, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testListGivesOnlyTheUsersMeasurementsMostRecentFirst(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('list');

        self::assertSame(
            [$this->id('boundary'), $this->id('imported'), $this->id('set_aside'), $this->id('evening'), $this->id('morning'), $this->id('waist')],
            array_column($data['measurements'], 'id'),
        );
        self::assertSame('Health Connect', $data['measurements'][1]['sourceLabel']);
        self::assertTrue($data['measurements'][2]['excluded']);
        self::assertSame(21.3, $data['measurements'][4]['bodyFatPercent']);
    }

    public function testListHonoursItsFilters(): void
    {
        $this->loginFixtureUser();

        self::assertSame([$this->id('boundary'), $this->id('imported'), $this->id('set_aside')], array_column($this->call('list', after: '2026-10-22T00:00:00+02:00')['measurements'], 'id'));
        self::assertSame([$this->id('evening'), $this->id('morning'), $this->id('waist')], array_column($this->call('list', before: '2026-10-22T00:00:00+02:00')['measurements'], 'id'));
        self::assertCount(2, $this->call('list', limit: 2)['measurements']);
        self::assertSame('after', $this->call('list', after: '2026-10-22')['field']);
    }

    public function testExcludeSetsTheMeasurementAsideAndPublishes(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('exclude', measurementId: $this->id('morning'));

        self::assertTrue($data['success']);
        self::assertTrue($data['measurement']['excluded']);
        $morning = $this->reload('morning');
        self::assertInstanceOf(BodyMeasurement::class, $morning);
        self::assertTrue($morning->isExcluded());
        self::assertSame(72.4, $morning->getWeightKg());
        $this->assertMercureUpdatePublished('/api/body-measurements/'.$this->id('morning'));
    }

    public function testAnUpdateWithExcludedFalseTakesItBack(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', measurementId: $this->id('set_aside'), excluded: false);

        self::assertTrue($data['success']);
        self::assertFalse($this->reload('set_aside')?->isExcluded());
    }

    public function testUpdateChangesOnlyWhatIsGiven(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', measurementId: $this->id('morning'), weightKg: 72.5);

        self::assertTrue($data['success']);
        $morning = $this->reload('morning');
        self::assertInstanceOf(BodyMeasurement::class, $morning);
        self::assertSame(72.5, $morning->getWeightKg());
        self::assertSame(21.3, $morning->getBodyFatPercent());
        self::assertFalse($morning->isExcluded());
        $this->assertMercureUpdatePublished('/api/body-measurements/'.$this->id('morning'));
    }

    public function testAnUpdateWithNothingToChangeAnswersTheMeasurement(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', measurementId: $this->id('morning'));

        self::assertStringContainsString('Nothing to change', $data['error']);
        self::assertSame(72.4, $data['current']['weightKg']);
        $this->assertMercureUpdateCount(0);
    }

    public function testARefusedUpdateAnswersTheMeasurementAsItIs(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', measurementId: $this->id('morning'), weightKg: -2.0);

        self::assertSame('weightKg', $data['field']);
        self::assertSame(72.4, $data['current']['weightKg']);
        self::assertSame(72.4, $this->reload('morning')?->getWeightKg());
        $this->assertMercureUpdateCount(0);
    }

    public function testUpdateExcludeAndDeleteNeedTheId(): void
    {
        $this->loginFixtureUser();

        self::assertSame('measurementId', $this->call('update', weightKg: 70.0)['field']);
        self::assertSame('measurementId', $this->call('exclude')['field']);
        self::assertSame('measurementId', $this->call('delete')['field']);
    }

    public function testDeleteRemovesAndPublishes(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('delete', measurementId: $this->id('evening'));

        self::assertTrue($data['success']);
        self::assertNull($this->reload('evening'));
        $this->assertMercureDeletePublished('/api/body-measurements/'.$this->id('evening'));
    }

    public function testAnotherUsersMeasurementIsNotFound(): void
    {
        $this->loginFixtureUser();

        $update = $this->call('update', measurementId: $this->id('secret'), weightKg: 60.0);
        $exclude = $this->call('exclude', measurementId: $this->id('secret'));
        $delete = $this->call('delete', measurementId: $this->id('secret'));

        self::assertStringContainsString('Measurement not found', $update['error']);
        self::assertStringContainsString('Measurement not found', $exclude['error']);
        self::assertStringContainsString('Measurement not found', $delete['error']);
        $secret = $this->reload('secret');
        self::assertSame(80.0, $secret?->getWeightKg());
        self::assertFalse($secret?->isExcluded());
        $this->assertMercureUpdateCount(0);
    }
}
