<?php

namespace Maggie\Health\Tests\MessageHandler;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutFeeling;
use Maggie\Health\Exception\DuplicateWorkoutException;
use Maggie\Health\Exception\InvalidWorkoutException;
use Maggie\Health\Message\UpdateWorkoutCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateWorkoutHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/workout.yaml');
        $this->resetMercure();
    }

    private function reload(string $fixture): Workout
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();
        $workout = $em->find(Workout::class, (string) $this->getFixture($fixture)->getId());
        \assert($workout instanceof Workout);

        return $workout;
    }

    private function dispatch(string $fixture, string $owner = 'test_user', mixed ...$fields): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new UpdateWorkoutCommand(...[
            'userId' => (string) $this->getFixture($owner)->getId(),
            'workoutId' => (string) $this->getFixture($fixture)->getId(),
        ] + $fields));
    }

    public function testOnlyTheGivenFieldsChangeAndItPublishes(): void
    {
        $this->dispatch('run', title: 'Footing', feeling: 'hard');

        $run = $this->reload('run');
        self::assertSame('Footing', $run->getTitle());
        self::assertSame(WorkoutFeeling::Hard, $run->getFeeling());
        self::assertSame(32, $run->getDurationMinutes());
        self::assertSame(5400, $run->getDistanceMeters());
        $this->assertMercureUpdatePublished('/api/workouts/'.$run->getId());
    }

    public function testAClearFlagEmptiesAnOptionalField(): void
    {
        $this->dispatch('run', clearKcal: true, clearFeeling: true);

        $run = $this->reload('run');
        self::assertNull($run->getKcal());
        self::assertNull($run->getFeeling());
        self::assertSame(152, $run->getAvgHeartRate());
    }

    public function testARefusalLeavesTheWorkoutAsItWas(): void
    {
        try {
            $this->dispatch('run', title: 'Footing', startedAt: '2026-10-22');
            self::fail('The command should be refused.');
        } catch (HandlerFailedException $e) {
            self::assertInstanceOf(InvalidWorkoutException::class, $e->getPrevious());
            self::assertSame('startedAt', $e->getPrevious()->field);
        }

        self::assertSame('Course', $this->reload('run')->getTitle());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnExternalIdOfAnotherWorkoutOfTheUserIsRefused(): void
    {
        try {
            $this->dispatch('strength', externalId: 'watch-4711');
            self::fail('The command should be refused.');
        } catch (HandlerFailedException $e) {
            self::assertInstanceOf(DuplicateWorkoutException::class, $e->getPrevious());
        }

        self::assertNull($this->reload('strength')->getExternalId());
    }

    public function testAnotherUsersWorkoutIsRefused(): void
    {
        try {
            $this->dispatch('secret', title: 'Volé');
            self::fail('The command should be refused.');
        } catch (HandlerFailedException $e) {
            self::assertStringContainsString('Workout not found', (string) $e->getPrevious()?->getMessage());
        }

        self::assertSame('Séance secrète', $this->reload('secret')->getTitle());
        $this->assertMercureUpdateCount(0);
    }
}
