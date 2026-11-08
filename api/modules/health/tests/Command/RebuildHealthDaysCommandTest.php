<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Command;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\HealthDay;
use Maggie\Health\Enum\HealthDayAuthor;
use Maggie\Health\Message\UpdateHealthDayCommand;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Tester\CommandTester;
use Symfony\Component\Messenger\MessageBusInterface;

/** app:health:rebuild-days gives back the journal the events laid down, and never touches what was typed (MAG-750). */
class RebuildHealthDaysCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-25T21:42:00+02:00')));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/health_day.yaml');
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

    private function userId(): string
    {
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);

        return (string) $user->getId();
    }

    /** @return array<string, array<string, mixed>> every journal row, by user and date, without its id */
    private function journal(): array
    {
        $this->em()->clear();
        $rows = [];
        foreach ($this->em()->getRepository(HealthDay::class)->findAll() as $day) {
            $entry = $day->describe();
            unset($entry['id']);
            $rows[$day->getUser()->getId().' '.$entry['date']] = $entry;
        }
        ksort($rows);

        return $rows;
    }

    /** @param array<string, string> $input */
    private function rebuild(array $input = []): CommandTester
    {
        $tester = new CommandTester((new Application(self::$kernel))->find('app:health:rebuild-days'));
        $tester->execute($input);

        return $tester;
    }

    public function testTheHistoryIsRebuiltIdenticallyAndTheTypedValuesStay(): void
    {
        $bus = self::getContainer()->get('messenger.bus.default');
        \assert($bus instanceof MessageBusInterface);
        $bus->dispatch(new UpdateHealthDayCommand(userId: $this->userId(), date: '2026-10-20', author: HealthDayAuthor::Maggie, mood: 7, fatigue: 4, note: 'Bonne journée'));
        $before = $this->journal();
        self::assertNotEmpty($before);

        // The computed columns are lost (a migration, a bug), the typed ones are not.
        $this->em()->getConnection()->executeStatement('UPDATE health_day SET weight_kg = NULL, body_fat_percent = NULL, weighed_at = NULL, steps = NULL, eaten_kcal = NULL, expended_kcal = NULL, sport_minutes = NULL');
        // And a row the events never laid down, with nothing in it.
        $this->em()->getConnection()->executeStatement('DELETE FROM health_day WHERE date = :date', ['date' => '2026-10-21']);
        $this->em()->clear();

        $tester = $this->rebuild();

        self::assertSame(Command::SUCCESS, $tester->getStatusCode());
        self::assertSame($before, $this->journal());
        $day = $this->em()->getRepository(HealthDay::class)->findOneBy(['user' => $this->userId(), 'date' => new \DateTimeImmutable('2026-10-20')]);
        self::assertSame(7, $day?->getMood());
        self::assertSame('Bonne journée', $day?->getNote());
        self::assertSame(HealthDayAuthor::Maggie, $day?->getNotedBy());
    }

    public function testRunningItTwiceChangesNothing(): void
    {
        $this->rebuild();
        $once = $this->journal();
        $this->rebuild();

        self::assertSame($once, $this->journal());
    }

    public function testItCanBeLimitedToOneUser(): void
    {
        $this->em()->getConnection()->executeStatement('DELETE FROM health_day');

        $tester = $this->rebuild(['--user' => 'health-day-test@example.com']);

        self::assertSame(Command::SUCCESS, $tester->getStatusCode());
        $users = array_unique(array_map(static fn (string $key): string => explode(' ', $key)[0], array_keys($this->journal())));
        self::assertSame([$this->userId()], array_values($users));
    }

    public function testAnUnknownUserFails(): void
    {
        self::assertSame(Command::FAILURE, $this->rebuild(['--user' => 'nobody@example.com'])->getStatusCode());
    }
}
