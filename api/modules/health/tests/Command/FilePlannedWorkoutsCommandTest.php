<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Health\Entity\Workout;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Tester\CommandTester;

/** app:health:file-planned-workouts files the planned sessions stored before the « Santé » agenda (MAG-777). */
class FilePlannedWorkoutsCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/workout.yaml');
        // Stored before the agenda existed: no event yet.
        $this->em()->getConnection()->executeStatement('DELETE FROM event WHERE activity = ?', [Event::ACTIVITY_SPORT]);
        $this->em()->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function runCommand(array $options = []): CommandTester
    {
        $tester = new CommandTester((new Application(self::$kernel))->find('app:health:file-planned-workouts'));
        $tester->execute($options);

        return $tester;
    }

    private function plannedEvent(): ?Event
    {
        $this->em()->clear();
        $workout = $this->em()->find(Workout::class, (string) $this->getFixture('planned')->getId());
        \assert($workout instanceof Workout);

        return $workout->getEvent();
    }

    public function testItFilesTheMissingEventsThenHasNothingLeftToDo(): void
    {
        self::assertNull($this->plannedEvent());

        $first = $this->runCommand();

        self::assertSame(Command::SUCCESS, $first->getStatusCode());
        self::assertStringContainsString('1 planned workout(s) filed', $first->getDisplay());
        $event = $this->plannedEvent();
        self::assertSame('Marche rapide', $event?->getSummary());
        self::assertSame(Event::ACTIVITY_SPORT, $event?->getActivity());
        // Only the planned walk: the run, the strength session and the other user's are done.
        self::assertCount(1, $this->em()->getRepository(Event::class)->findBy(['activity' => Event::ACTIVITY_SPORT]));
        $this->assertMercurePublishedOnce('/api/events/'.$event?->getId());
        $this->assertElasticsearchIndexDispatchedFor(Event::class, (string) $event?->getId());

        $this->resetMercure();
        $second = $this->runCommand();

        self::assertStringContainsString('already in its module agenda', $second->getDisplay());
        $this->assertMercureUpdateCount(0);
        self::assertSame((string) $event?->getId(), (string) $this->plannedEvent()?->getId());
    }

    public function testADryRunChangesNothing(): void
    {
        $tester = $this->runCommand(['--dry-run' => true]);

        self::assertStringContainsString('[dry-run] 1 planned workout(s)', $tester->getDisplay());
        self::assertNull($this->plannedEvent());
    }
}
