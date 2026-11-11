<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Entity\WorkoutMakeup;
use Maggie\Health\Enum\WorkoutMakeupStatus;
use Maggie\Health\Mcp\Tool\ChooseWorkoutMakeupTool;
use Maggie\Health\Reminder\MissedWorkoutReminderSource;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** choose_workout_makeup (MAG-771): the same answers as POST /api/workout-makeups/{id}/choose. */
final class WorkoutMakeupToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use SecurityTokenTrait;

    private const NOW = '2026-11-10 18:46:00 UTC';

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(self::NOW));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/workout_makeup.yaml');
        $source = self::getContainer()->get(MissedWorkoutReminderSource::class);
        \assert($source instanceof MissedWorkoutReminderSource);
        $source->remind(new \DateTimeImmutable(self::NOW));
        $this->resetMercure();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testWithoutAUserItAnswersAnError(): void
    {
        self::assertArrayHasKey('error', $this->call($this->proposalId(), 'shorter'));
        self::assertSame(WorkoutMakeupStatus::Proposed, $this->proposal()->getStatus());
    }

    public function testChoosingPlansTheMakeup(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(WorkoutMakeup::iri($this->proposal()), 'softer');

        self::assertTrue($data['success']);
        self::assertSame('chosen', $data['makeup']['status']);
        self::assertSame('softer', $data['workout']['makeupOption']);
        self::assertSame(45, $data['workout']['durationMinutes']);
        self::assertSame((string) $this->getFixture('tuesday')->getId(), $data['workout']['makeupOf']);
        $planned = $this->em()->find(Workout::class, $data['workout']['id']);
        self::assertInstanceOf(Workout::class, $planned);
        self::assertSame((string) $this->getFixture('tuesday')->getId(), (string) $planned->getMakeupOf()?->getId());
        self::assertSame(WorkoutMakeupStatus::Chosen, $this->proposal()->getStatus());
        $this->assertMercureUpdatePublished('/api/workout-makeups/');
        $this->assertMercureUpdatePublished('/api/workouts/');
    }

    public function testRefusalsChangeNothing(): void
    {
        $this->loginFixtureUser();

        self::assertSame('option', $this->call($this->proposalId(), 'later')['field']);
        self::assertStringContainsString('not found', $this->call((string) $this->getFixture('other_makeup')->getId(), 'tomorrow')['error']);
        self::assertStringContainsString('not found', $this->call('not-an-id', 'tomorrow')['error']);
        $this->assertMercureUpdateCount(0);

        $this->call($this->proposalId(), 'tomorrow');
        self::assertSame(409, $this->call($this->proposalId(), 'softer')['status']);
        self::assertCount(1, $this->em()->getRepository(Workout::class)->findBy(['makeupOf' => $this->getFixture('tuesday')->getId()]));
    }

    /** @return array<string, mixed> */
    private function call(string $makeupId, string $option): array
    {
        $tool = self::getContainer()->get(ChooseWorkoutMakeupTool::class);
        \assert($tool instanceof ChooseWorkoutMakeupTool);

        return json_decode($tool($makeupId, $option), true, 512, JSON_THROW_ON_ERROR);
    }

    private function proposal(): WorkoutMakeup
    {
        $makeup = $this->em()->getRepository(WorkoutMakeup::class)->findOneBy(['missed' => $this->getFixture('tuesday')->getId()]);
        \assert($makeup instanceof WorkoutMakeup);

        return $makeup;
    }

    private function proposalId(): string
    {
        return (string) $this->proposal()->getId();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }
}
