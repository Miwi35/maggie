<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Period;
use Maggie\Calendar\Enum\PeriodHoliday;
use Maggie\Calendar\Holiday\FrenchHolidaysClient;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Tester\CommandTester;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;

/**
 * `maggie:calendar:import-holidays` (MAG-810): a year of holidays, France's
 * and Geneva's, one all-day period a day in each user's « Fériés » agenda.
 * The French API answers through a MockHttpClient.
 */
final class ImportHolidaysCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    /** What calendrier.api.gouv.fr answers for 2027. */
    private const array FRANCE_2027 = [
        '2027-01-01' => '1er janvier',
        '2027-03-29' => 'Lundi de Pâques',
        '2027-05-01' => '1er mai',
        '2027-05-06' => 'Ascension',
        '2027-05-08' => '8 mai',
        '2027-05-17' => 'Lundi de Pentecôte',
        '2027-07-14' => '14 juillet',
        '2027-08-15' => 'Assomption',
        '2027-11-01' => 'Toussaint',
        '2027-11-11' => '11 novembre',
        '2027-12-25' => 'Jour de Noël',
    ];

    /** France's eleven, and Geneva's four of its own: Good Friday, 1 August, the Jeûne, 31 December. */
    private const int DAYS_2027 = 15;

    /** @var list<string> */
    private array $requested = [];

    private bool $franceDown = false;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('ImportHolidaysCommandTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->requested = [];
        $this->franceDown = false;

        $http = new MockHttpClient(function (string $method, string $url): MockResponse {
            $this->requested[] = $url;
            if ($this->franceDown) {
                return new MockResponse('Service Unavailable', ['http_code' => 503]);
            }

            return new MockResponse(json_encode(str_contains($url, '/2027.json') ? self::FRANCE_2027 : [], JSON_THROW_ON_ERROR));
        });
        self::getContainer()->set(FrenchHolidaysClient::class, new FrenchHolidaysClient($http, 'http://french-holidays.test/jours-feries/metropole'));
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** @param array<string, string> $arguments */
    private function import(array $arguments = ['year' => '2027']): CommandTester
    {
        $tester = new CommandTester((new Application(self::$kernel))->find('maggie:calendar:import-holidays'));
        $tester->execute($arguments);
        $this->em()->clear();

        return $tester;
    }

    private function holidaysAgendaOf(string $userRef): ?Agenda
    {
        $user = $this->getFixture($userRef);
        \assert($user instanceof User);

        return $this->em()->getRepository(Agenda::class)->findOneBy(['user' => $user, 'module' => Agenda::MODULE_HOLIDAYS]);
    }

    /** @return array<string, Period> by first day */
    private function holidaysOf(string $userRef): array
    {
        $agenda = $this->holidaysAgendaOf($userRef);
        self::assertNotNull($agenda, "{$userRef} has no « Fériés » agenda");

        $periods = [];
        foreach ($this->em()->getRepository(Period::class)->findBy(['agenda' => $agenda]) as $period) {
            $periods[$period->getStartDate()?->format('Y-m-d')] = $period;
        }
        ksort($periods);

        return $periods;
    }

    public function testImportCreatesOneAllDayPeriodPerHolidayInTheHolidaysAgenda(): void
    {
        $tester = $this->import();

        self::assertSame(Command::SUCCESS, $tester->getStatusCode(), $tester->getDisplay());
        self::assertSame(['http://french-holidays.test/jours-feries/metropole/2027.json'], $this->requested);

        $agenda = $this->holidaysAgendaOf('test_user');
        self::assertNotNull($agenda);
        self::assertSame('Fériés', $agenda->getName());
        self::assertFalse($agenda->isDefault());

        foreach (['test_user', 'other_user'] as $user) {
            $periods = $this->holidaysOf($user);
            self::assertCount(self::DAYS_2027, $periods);
            foreach ($periods as $day => $period) {
                self::assertTrue($period->isAllDay(), $day);
                self::assertNull($period->getRrule(), $day);
                self::assertNotNull($period->getHoliday(), $day);
                self::assertSame((new \DateTimeImmutable($day))->modify('+1 day')->format('Y-m-d'), $period->getEndDate()?->format('Y-m-d'), $day);
            }
        }
        self::assertSame(2 * self::DAYS_2027, $this->em()->getRepository(Period::class)->count([]));
        self::assertSame('11 novembre', $this->holidaysOf('test_user')['2027-11-11']->getName());
    }

    public function testADayOnBothSidesIsOnePeriodBoth(): void
    {
        $this->import();

        $holidays = array_map(static fn (Period $period): ?PeriodHoliday => $period->getHoliday(), $this->holidaysOf('test_user'));

        foreach (['2027-01-01', '2027-03-29', '2027-05-06', '2027-05-17', '2027-12-25'] as $day) {
            self::assertSame(PeriodHoliday::Both, $holidays[$day], $day);
        }
        foreach (['2027-05-08', '2027-11-11', '2027-07-14'] as $day) {
            self::assertSame(PeriodHoliday::France, $holidays[$day], $day);
        }
        foreach (['2027-03-26', '2027-08-01', '2027-09-09', '2027-12-31'] as $day) {
            self::assertSame(PeriodHoliday::Geneve, $holidays[$day], $day);
        }

        // A shared day is named after France; a day of Geneva alone after its table.
        self::assertSame('Jour de Noël', $this->holidaysOf('test_user')['2027-12-25']->getName());
        self::assertSame('Jeûne genevois', $this->holidaysOf('test_user')['2027-09-09']->getName());
    }

    public function testASecondRunCreatesNothing(): void
    {
        $this->import();
        $this->resetMercure();

        $tester = $this->import();

        self::assertSame(Command::SUCCESS, $tester->getStatusCode(), $tester->getDisplay());
        self::assertSame(2 * self::DAYS_2027, $this->em()->getRepository(Period::class)->count([]));
        self::assertSame(2, $this->em()->getRepository(Agenda::class)->count(['module' => Agenda::MODULE_HOLIDAYS]));
        $this->assertNothingPublishedOn(Period::COLLECTION);
    }

    public function testARenamedHolidayKeepsItsName(): void
    {
        $this->import();
        $christmas = $this->holidaysOf('test_user')['2027-12-25'];
        $christmas->setName('Noël chez mamie');
        // As if Geneva had been missed: the import corrects whose holiday it is, never the name.
        $christmas->setHoliday(PeriodHoliday::France);
        $this->em()->flush();
        $this->em()->clear();

        $this->import();

        $christmas = $this->holidaysOf('test_user')['2027-12-25'];
        self::assertSame('Noël chez mamie', $christmas->getName());
        self::assertSame(PeriodHoliday::Both, $christmas->getHoliday());
        self::assertCount(self::DAYS_2027, $this->holidaysOf('test_user'));
    }

    public function testTheFrenchApiDownFailsAndStillImportsGeneva(): void
    {
        $this->franceDown = true;

        $tester = $this->import();

        self::assertSame(Command::FAILURE, $tester->getStatusCode());
        self::assertStringContainsString('French holidays of 2027 not imported', $tester->getDisplay());

        // Geneva's nine, nothing of France's: not a single French-only day.
        $holidays = array_map(static fn (Period $period): ?string => $period->getHoliday()?->value, $this->holidaysOf('test_user'));
        self::assertCount(9, $holidays);
        self::assertSame(['geneve'], array_values(array_unique($holidays)));
        self::assertArrayNotHasKey('2027-11-11', $holidays);

        // France back: its days are added, the shared ones turn `both`.
        $this->franceDown = false;
        self::assertSame(Command::SUCCESS, $this->import()->getStatusCode());
        $holidays = $this->holidaysOf('test_user');
        self::assertCount(self::DAYS_2027, $holidays);
        self::assertSame(PeriodHoliday::Both, $holidays['2027-01-01']->getHoliday());
    }

    public function testTheDefaultYearIsNextYear(): void
    {
        Clock::set(new MockClock('2026-12-01 04:12:00 UTC'));

        $this->import([]);

        self::assertSame(['http://french-holidays.test/jours-feries/metropole/2027.json'], $this->requested);
        self::assertArrayHasKey('2027-09-09', $this->holidaysOf('test_user'));
    }

    public function testABadYearIsRefused(): void
    {
        $tester = $this->import(['year' => 'next']);

        self::assertSame(Command::INVALID, $tester->getStatusCode());
        self::assertSame([], $this->requested);
        self::assertSame(0, $this->em()->getRepository(Period::class)->count([]));
    }

    public function testImportPublishesOnPeriods(): void
    {
        $this->import();

        $this->assertMercureUpdatePublished(Period::COLLECTION);
        $this->assertElasticsearchIndexDispatched(Period::class);
    }
}
