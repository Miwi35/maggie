<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Period;
use Maggie\Calendar\Mcp\Tool\ManagePeriodsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** manage_periods (MAG-809): every action, and partial requests refused with `current`. */
class PeriodToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/PeriodApiTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManagePeriodsTool::class);
        \assert($tool instanceof ManagePeriodsTool);

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

    private function reload(string $fixture): ?Period
    {
        return $this->em()->find(Period::class, $this->id($fixture));
    }

    private function rows(): int
    {
        return $this->em()->getRepository(Period::class)->count([]);
    }

    public function testMissingUserAnswersAnError(): void
    {
        $data = $this->call('list');

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('periods', $data);
    }

    public function testListExpandsTheRecurrenceBetweenFromAndTo(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('list', from: '2037-03-01', to: '2037-04-11');

        $names = array_column($data['periods'], 'name');
        self::assertSame(['Garde de Léo', 'Garde de Léo', 'Garde de Léo', 'Vacances de printemps'], $names);
        self::assertSame(['2037-03-06T18:00:00+01:00', '2037-03-20T18:00:00+01:00', '2037-04-03T18:00:00+02:00'], array_column(\array_slice($data['periods'], 0, 3), 'startAt'));
        self::assertSame('vendredi', $data['periods'][2]['startWeekday']);
        self::assertSame(array_fill(0, 3, $this->id('garde')), array_column(\array_slice($data['periods'], 0, 3), 'id'));
        self::assertSame('2037-04-19', $data['periods'][3]['lastDay']);
    }

    public function testListWithHalfARangeAnswersErrorAndCurrent(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('list', from: '2037-03-01');

        self::assertArrayHasKey('error', $data);
        self::assertSame(['from' => '2037-03-01'], $data['current']);
    }

    public function testListWithoutRangeGivesEveryPeriodOnce(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('list');

        self::assertSame(['Garde de Léo', 'Vacances de printemps'], array_column($data['periods'], 'name'));
        self::assertSame('FREQ=WEEKLY;INTERVAL=2', $data['periods'][0]['rrule']);
    }

    public function testCreateWithDaysStoresTheExcludedEnd(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'Vacances à la mer', agenda_id: 'famille', all_day: true, start_date: '2037-07-20', end_date: '2037-07-28');

        self::assertTrue($data['success'] ?? false, json_encode($data, JSON_THROW_ON_ERROR));
        self::assertSame('2037-07-27', $data['period']['lastDay']);
        self::assertSame('Famille', $data['period']['agenda']);

        $period = $this->em()->getRepository(Period::class)->findOneBy(['name' => 'Vacances à la mer']);
        self::assertInstanceOf(Period::class, $period);
        self::assertSame('2037-07-20', $period->getStartDate()?->format('Y-m-d'));
        self::assertSame('2037-07-28', $period->getEndDate()?->format('Y-m-d'));
        self::assertSame($this->id('famille'), (string) $period->getAgenda()->getId());
        $this->assertMercureUpdatePublished(Period::COLLECTION.'/'.$period->getId());
        $this->assertElasticsearchIndexDispatchedFor(Period::class, (string) $period->getId());
    }

    public function testCreateWithoutNameAnswersErrorAndCurrent(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', agenda_id: $this->id('perso'), all_day: true, start_date: '2037-07-20', end_date: '2037-07-28');

        self::assertStringContainsString('name', $data['error']);
        self::assertSame(['agenda_id' => $this->id('perso'), 'start_date' => '2037-07-20', 'end_date' => '2037-07-28', 'all_day' => true], $data['current']);
        self::assertSame(3, $this->rows());
    }

    public function testCreateWithAnIncompleteScheduleAnswersErrorAndCurrent(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'Déplacement à Lyon', agenda_id: 'Perso', start_date: '2037-05-04', start_time: '08:00');

        self::assertStringContainsString('end_date', $data['error']);
        self::assertSame('Déplacement à Lyon', $data['current']['name']);
        self::assertSame(3, $this->rows());
    }

    public function testUpdateChangesOnlyTheGivenFields(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', period_id: $this->id('garde'), name: 'Garde', rrule: '');

        self::assertTrue($data['success'] ?? false, json_encode($data, JSON_THROW_ON_ERROR));
        $garde = $this->reload('garde');
        self::assertSame('Garde', $garde?->getName());
        self::assertNull($garde?->getRrule());
        self::assertEquals(new \DateTimeImmutable('2037-03-06T18:00:00+01:00'), $garde?->getStartAt());
        self::assertSame($this->id('famille'), (string) $garde?->getAgenda()->getId());
        $this->assertMercureUpdatePublished(Period::COLLECTION.'/'.$this->id('garde'));
    }

    public function testARefusedUpdateAnswersErrorAndCurrent(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', period_id: $this->id('garde'), start_date: '2037-03-07');

        self::assertArrayHasKey('error', $data);
        self::assertSame('Garde de Léo', $data['current']['name']);
        self::assertEquals(new \DateTimeImmutable('2037-03-06T18:00:00+01:00'), $this->reload('garde')?->getStartAt());
    }

    public function testDeleteRemovesThePeriod(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('delete', period_id: $this->id('vacances'));

        self::assertTrue($data['success'] ?? false);
        self::assertNull($this->reload('vacances'));
        $this->assertMercureDeletePublished(Period::COLLECTION.'/'.$this->id('vacances'));
        $this->assertElasticsearchDeleteDispatched('periods');
    }

    public function testAnotherUsersPeriodIsNotFound(): void
    {
        $this->loginFixtureUser();

        $update = $this->call('update', period_id: $this->id('secret'), name: 'Volée');
        $delete = $this->call('delete', period_id: $this->id('secret'));
        $create = $this->call('create', name: 'Intrus', agenda_id: $this->id('other_agenda'), all_day: true, start_date: '2037-07-20', end_date: '2037-07-21');

        self::assertStringContainsString('not found', $update['error']);
        self::assertStringContainsString('not found', $delete['error']);
        self::assertArrayHasKey('error', $create);
        self::assertSame('Période secrète', $this->reload('secret')?->getName());
        self::assertSame(3, $this->rows());
        self::assertNotContains('Période secrète', array_column($this->call('list')['periods'], 'name'));
    }
}
